package com.morpheuslab.argocd

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

/**
 * Actions of an Argo CD integration page: install or remove Argo CD on an HKS cluster, connect this integration to
 * it, or to an Argo CD the customer already has. Each integration is one Argo CD; its connection lives in Cypher.
 * All actions need the Argo CD Admin permission and an integration of the user's own tenant (or the main tenant).
 */
@Slf4j
class ArgoCdSetupController implements PluginController {

    static final String URL_RE = /^https?:\/\/[A-Za-z0-9.\-]+(:\d{1,5})?(\/[\w.\-\/]*)?$/
    static final String NO_MORPHEUS = 'Could not reach Morpheus with your session. Reload the page and try again.'

    Plugin plugin
    MorpheusContext morpheus

    ArgoCdSetupController(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    String getCode() { 'argocd-setup-controller' }
    String getName() { 'Argo CD Setup Controller' }
    MorpheusContext getMorpheus() { morpheus }
    Plugin getPlugin() { plugin }

    List<Route> getRoutes() {
        Permission admin = Permission.build(ArgoCdPlugin.PERMISSION_ADMIN, 'full')
        [
            Route.build('/argocd/setup/install', 'install', admin),
            Route.build('/argocd/setup/remove', 'remove', admin),
            Route.build('/argocd/setup/connect', 'connect', admin),
            Route.build('/argocd/setup/save', 'save', admin),
            Route.build('/argocd/setup/disconnect', 'disconnect', admin)
        ]
    }

    def install(ViewModel<Map> model) {
        Map ctx = start(model, true)
        if (ctx.denied) return ctx.denied
        ComputeServerGroup c = ctx.cluster
        AccountIntegration integ = ctx.integration
        // one job at a time: installs on the same cluster would clash
        if (Activity.anyRunning || !Activity.start(c.id, 'Installing')) return toPage(model, 'warning', 'Another install or remove is running. Try again when it is done.', 'clusters')
        Map cur = Instances.connection(morpheus, integ)
        Kube kube = Kube.of(morpheus, c)
        String refused = null
        if (cur) refused = "${integ.name} is connected to ${cur.url} already. Disconnect it first, or add another Argo CD integration."
        else if (!kube.usable) refused = "Morpheus has no API access for ${c.name} yet."
        else if (Installer.status(kube).state != 'none') refused = "${c.name} already runs Argo CD."
        else refused = Installer.conflict(kube)
        if (refused) {
            Activity.done(c.id)
            return toPage(model, 'warning', refused, 'clusters')
        }
        String user = model.user?.username
        String apiHost = SetupPage.host(c.serviceUrl)
        List<String> nodeHosts = SetupPage.nodeHosts(morpheus, c)
        Thread.start("argocd-install-${c.id}") {
            Closure say = { String line -> Activity.say(c.id, c.name, line) }
            try {
                say("Installing Argo CD ${Installer.bundledVersion()} for ${integ.name} (started by ${user})")
                Map r = Installer.install(kube, nodeHosts, apiHost, say, integ.id)
                if (r.error) {
                    say("Install failed: ${r.error}")
                } else {
                    String err = Instances.save(morpheus, integ, [url: r.url, token: r.token, verifyTls: false, project: 'default'])
                    say(err ? "Argo CD is installed, but ${err} Press Connect." : "Argo CD is ready at ${r.url}. ${integ.name} uses it now.")
                }
                log.info("ArgoCD: ${user} installed Argo CD on cluster ${c.id} for integration ${integ.id} -> ${r.error ?: 'ok'}")
            } catch (Throwable t) {
                log.error("ArgoCD: install on cluster ${c.id} failed", t)
                say("Install failed: ${t.message}")
            } finally {
                Activity.done(c.id)
            }
        }
        toPage(model, 'info', "Installing Argo CD on ${c.name}. This takes a few minutes, longer when the nodes download images slowly. Reload the page to follow it.", 'clusters')
    }

    def remove(ViewModel<Map> model) {
        Map ctx = start(model, true)
        if (ctx.denied) return ctx.denied
        ComputeServerGroup c = ctx.cluster
        AccountIntegration integ = ctx.integration
        if (param(model, 'confirm') != c.name) return toPage(model, 'danger', 'The name you typed does not match the cluster.', 'clusters')
        if (Activity.anyRunning || !Activity.start(c.id, 'Removing')) return toPage(model, 'warning', 'Another install or remove is running. Try again when it is done.', 'clusters')
        Kube kube = Kube.of(morpheus, c)
        Map st = SetupPage.liveOwner(Installer.status(kube), Instances.integrations(morpheus)*.id)
        String refused = st.state != 'ours' ? "Argo CD on ${c.name} was not installed by this plugin, so it stays." :
            (st.owner && st.owner != integ.id ? "Argo CD on ${c.name} belongs to another Argo CD integration. Remove it from that page." :
            (!st.owner && !ctx.account?.masterAccount ? "Argo CD on ${c.name} was installed before Argo CD integrations. Only the main tenant can remove it." : null))
        if (refused) {
            Activity.done(c.id)
            return toPage(model, 'warning', refused, 'clusters')
        }
        Map cur = Instances.connection(morpheus, integ)
        boolean inUse = cur && SetupPage.usesThis(cur.url as String, st, SetupPage.nodeHosts(morpheus, c) + [SetupPage.host(c.serviceUrl)])
        String user = model.user?.username
        Thread.start("argocd-remove-${c.id}") {
            Closure say = { String line -> Activity.say(c.id, c.name, line) }
            try {
                say("Removing Argo CD (started by ${user})")
                String err = Installer.remove(kube, say)
                if (err) say(err)
                if (!err && inUse) say(Instances.clear(morpheus, integ) ?: "The connection of ${integ.name} is cleared.")
                log.info("ArgoCD: ${user} removed Argo CD from cluster ${c.id} -> ${err ?: 'ok'}")
            } catch (Throwable t) {
                log.error("ArgoCD: remove on cluster ${c.id} failed", t)
                say("Remove failed: ${t.message}")
            } finally {
                Activity.done(c.id)
            }
        }
        toPage(model, 'info', "Removing Argo CD from ${c.name}. Reload the page to follow it.", 'clusters')
    }

    /** Use the Argo CD this plugin installed on a cluster, with the token the install saved there (or a new one). */
    def connect(ViewModel<Map> model) {
        Map ctx = start(model, true)
        if (ctx.denied) return ctx.denied
        ComputeServerGroup c = ctx.cluster
        AccountIntegration integ = ctx.integration
        Kube kube = Kube.of(morpheus, c)
        Map st = SetupPage.liveOwner(Installer.status(kube), Instances.integrations(morpheus)*.id)
        if (st.owner && st.owner != integ.id) return toPage(model, 'warning', "Argo CD on ${c.name} belongs to another Argo CD integration.", 'clusters')
        if (!st.owner && !ctx.account?.masterAccount) return toPage(model, 'warning', "Argo CD on ${c.name} was installed before Argo CD integrations. Only the main tenant can connect it.", 'clusters')
        Map saved = Installer.connectToken(kube)
        if (saved.error) return toPage(model, 'danger', "${saved.error} Make a token for the account morpheus in Argo CD and save it under Connection.", 'connection')
        String err = Installer.unsafeUrl(saved.url as String) ?: testAndSave(integ, saved.url as String, saved.token as String, false, 'default')
        audit(model, "connect integration=${integ.id} cluster=${c.id}", err)
        err ? toPage(model, 'danger', err, 'connection') : toPage(model, 'success', "${integ.name} now uses the Argo CD on ${c.name}.", 'connection')
    }

    /** Connect to an Argo CD the customer already has. An empty token keeps the saved one. */
    def save(ViewModel<Map> model) {
        Map ctx = start(model, false)
        if (ctx.denied) return ctx.denied
        AccountIntegration integ = ctx.integration
        String url = param(model, 'url')?.replaceAll('/+$', '')
        if (!url || !(url ==~ URL_RE)) return toPage(model, 'danger', 'Type the Argo CD address, for example https://argocd.example.com or https://10.0.0.5:30443.', 'connection')
        String unsafe = Installer.unsafeUrl(url)
        if (unsafe) return toPage(model, 'danger', unsafe, 'connection')
        Map cur = Instances.connection(morpheus, integ)
        String token = param(model, 'token') ?: (cur?.url == url ? cur.token as String : null)
        if (!token) return toPage(model, 'danger', 'Paste the API token of the Argo CD account the plugin should use.', 'connection')
        String project = param(model, 'project') ?: 'default'
        if (!(project ==~ ArgoCdController.NAME_RE)) return toPage(model, 'danger', 'The project name may only use lower case letters, digits, dots and dashes.', 'connection')
        String err = testAndSave(integ, url, token, param(model, 'verifyTls') == 'on', project)
        audit(model, "save integration=${integ.id} url=${url}", err)
        err ? toPage(model, 'danger', err, 'connection') : toPage(model, 'success', "Connected. ${integ.name} uses this Argo CD now.", 'connection')
    }

    def disconnect(ViewModel<Map> model) {
        Map ctx = start(model, false)
        if (ctx.denied) return ctx.denied
        AccountIntegration integ = ctx.integration
        String err = Instances.clear(morpheus, integ)
        audit(model, "disconnect integration=${integ.id}", err)
        err ? toPage(model, 'danger', err, 'connection') : toPage(model, 'success', "${integ.name} is not connected to an Argo CD any more. Argo CD itself was not changed.", 'connection')
    }

    // ---------- helpers ----------

    private String testAndSave(AccountIntegration integ, String url, String token, boolean verifyTls, String project) {
        ArgoCdClient client = ArgoCdClient.of(new ArgoCdSettings(url: url, token: token, verifyTls: verifyTls))
        ArgoResult ver = client.version()
        if (!ver.ok) return "Could not reach Argo CD at ${url}: ${ver.describe()}"
        ArgoResult who = client.userInfo()
        if (!SetupPage.loggedIn(who)) return "Argo CD did not accept the token: ${who.ok ? 'not logged in' : who.describe()}"
        Instances.save(morpheus, integ, [url: url, token: token, verifyTls: verifyTls, project: project])
    }

    /** Plugin settings with the given values (used to clear the old single connection). */
    static Map config(String url, String token, boolean verifyTls, String project) {
        [(ArgoCdSettings.URL_FIELD): url ?: '', (ArgoCdSettings.TOKEN_FIELD): token ?: '',
         (ArgoCdSettings.VERIFY_TLS_FIELD): verifyTls ? 'on' : 'off', (ArgoCdSettings.PROJECT_FIELD): project ?: 'default']
    }

    /**
     * Admin check, the integration (of the user's tenant or the main tenant), and when `needCluster` an HKS cluster
     * the user can see. Returns [integration, cluster, api] or [denied: response].
     */
    private Map start(ViewModel<Map> model, boolean needCluster) {
        String sub = needCluster ? 'clusters' : 'connection'
        if (!AdminAccess.canAdmin(model.user)) return [denied: toPage(model, 'danger', 'You need the Argo CD Admin permission.', sub)]
        SelfApi api = SelfApi.capture()
        if (!api) return [denied: toPage(model, 'danger', NO_MORPHEUS, sub)]
        String iid = param(model, 'integrationId')
        AccountIntegration integ = iid?.isLong() ? Instances.integration(morpheus, iid as Long) : null
        Account acc = Instances.accountOf(morpheus, model.user)
        boolean allowed = integ && acc && (acc.masterAccount || integ.account?.id == acc.id)
        if (!allowed) return [denied: toPage(model, 'danger', 'Argo CD integration not found.', sub)]
        if (!needCluster) return [integration: integ, api: api, account: acc]
        String id = param(model, 'clusterId')
        ComputeServerGroup c = id?.isLong() ? morpheus.services.cluster.get(id as Long) : null
        if (!c || !AdminAccess.isHks(c) || !api.seesCluster(c.id)) return [denied: toPage(model, 'danger', 'HKS cluster not found.', sub)]
        [integration: integ, cluster: c, api: api, account: acc]
    }

    private void audit(ViewModel<Map> model, String what, String err) {
        log.info("ArgoCD: ${model.user?.username} setup ${what} -> ${err ?: 'ok'}")
    }

    /** Back to the integration page with a message, on the given sub tab. */
    private static HTMLResponse toPage(ViewModel<Map> model, String level, String text, String sub) {
        Flash.set(level, text, sub)
        String id = param(model, 'integrationId')
        String url = id?.isLong() ? "/admin/integrations/${id}" : '/admin/integrations'
        try {
            def resp = model?.response
            if (resp != null && resp.respondsTo('sendRedirect') && !resp.committed) {
                resp.sendRedirect(url)
                return HTMLResponse.success('')
            }
        } catch (Throwable ignored) { }
        HTMLResponse.success("<!doctype html><html><head><meta http-equiv=\"refresh\" content=\"0;url=${url}\"><title>Argo CD</title></head>" +
            "<body><a href=\"${url}\">Back to Argo CD</a></body></html>")
    }

    private static String param(ViewModel<Map> model, String name) {
        try { (model.request?.getParameter(name) as String)?.trim() ?: null } catch (Throwable ignored) { null }
    }
}
