package com.morpheuslab.argocd

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

/**
 * Every action the Argo CD panel can take. Morpheus enforces the route permission (Roles > Argo CD
 * Applications) before a handler runs; mutating handlers additionally require POST (Morpheus checks
 * the CSRF token on POST). Each handler calls the Argo CD API server-side — the token never reaches
 * the browser — records the outcome in PanelState and sends the user back to the cluster tab.
 */
@Slf4j
class ArgoCdController implements PluginController {

    static final String NAME_RE = /^[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?$/

    Plugin plugin
    MorpheusContext morpheus

    ArgoCdController(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    @Override
    String getCode() { 'argocd-controller' }

    @Override
    String getName() { 'Argo CD Controller' }

    @Override
    MorpheusContext getMorpheus() { morpheus }

    @Override
    Plugin getPlugin() { plugin }

    @Override
    List<Route> getRoutes() {
        Permission read = Permission.build(ArgoCdPlugin.PERMISSION_APPS, 'read')
        Permission full = Permission.build(ArgoCdPlugin.PERMISSION_APPS, 'full')
        [
            Route.build('/argocd/ping', 'ping', read),
            Route.build('/argocd/select', 'select', read),
            Route.build('/argocd/close', 'close', read),
            Route.build('/argocd/refresh', 'refresh', read),
            Route.build('/argocd/sync', 'sync', full),
            Route.build('/argocd/delete', 'delete', full),
            Route.build('/argocd/create', 'create', full),
            Route.build('/argocd/resource-action', 'resourceAction', full)
        ]
    }

    // ---------- read ----------

    def ping(ViewModel<Map> model) {
        ArgoCdSettings s = ArgoCdSettings.read(morpheus, plugin)
        Map out = [ok: true, plugin: 'argocd', configured: s.configured, user: model.user?.username,
                   access: Access.level(model.user)]
        if (s.configured) {
            ArgoResult v = ArgoCdClient.of(s).version()
            out.argocd = v.ok ? v.data?.Version : v.describe()
        }
        JsonResponse.of(out)
    }

    /** Select an app (and optionally one of its resources) — Argo CD's "click a node". */
    def select(ViewModel<Map> model) {
        Map p = params(model)
        Long clusterId = p.clusterId as Long
        if (!Access.canRead(model.user)) return back(clusterId, 'top', model)
        PanelState.update(model.user?.id, clusterId) { PanelState.State s ->
            if (s.selectedApp != p.app) { s.selectedRef = null; s.selectedResource = null }
            s.selectedApp = p.app
            if (p.kind && p.name) {
                s.selectedRef = [kind: p.kind, name: p.name, namespace: p.namespace ?: '', group: p.group ?: '',
                                 version: p.version ?: '', uid: p.uid ?: '']
                s.selectedResource = p.uid ?: ResourceTree.key(p.kind, p.namespace, p.name)
            }
        }
        back(clusterId, 'app', model)
    }

    def close(ViewModel<Map> model) {
        Map p = params(model)
        Long clusterId = p.clusterId as Long
        if (!Access.canRead(model.user)) return back(clusterId, 'top', model)
        PanelState.update(model.user?.id, clusterId) { PanelState.State s ->
            if (p.what == 'resource') { s.selectedRef = null; s.selectedResource = null }
            else { s.selectedApp = null; s.selectedRef = null; s.selectedResource = null }
        }
        back(clusterId, p.what == 'resource' ? 'app' : 'apps', model)
    }

    def refresh(ViewModel<Map> model) {
        act(model, false) { ArgoCdClient c, Map p ->
            ArgoResult r = c.getApplication(p.app, p.hard == 'true' ? 'hard' : 'normal')
            r.ok ? ok("Refreshed ${p.app}${p.hard == 'true' ? ' (hard refresh)' : ''}.") : r
        }
    }

    // ---------- manage (full) ----------

    def sync(ViewModel<Map> model) {
        act(model, true) { ArgoCdClient c, Map p ->
            ArgoResult r = c.syncApplication(p.app, p.prune == 'true', model.user?.username)
            r.ok ? ok("Sync started for ${p.app}${p.prune == 'true' ? ' (with prune)' : ''}. Refresh to follow progress.") : r
        }
    }

    def delete(ViewModel<Map> model) {
        act(model, true) { ArgoCdClient c, Map p ->
            if (p.confirm != p.app) return fail("Type the application name exactly to delete it.")
            boolean cascade = p.cascade != 'false'
            ArgoResult r = c.deleteApplication(p.app, cascade)
            if (!r.ok) return r
            PanelState.update(model.user?.id, p.clusterId as Long) { PanelState.State s ->
                if (s.selectedApp == p.app) { s.selectedApp = null; s.selectedRef = null; s.selectedResource = null }
            }
            ok("Deleting ${p.app}${cascade ? ' and its Kubernetes resources' : ' (resources kept)'}.") + [keepOpen: false]
        }
    }

    def create(ViewModel<Map> model) {
        act(model, true) { ArgoCdClient c, Map p ->
            List<String> errors = []
            if (!(p.app ==~ NAME_RE)) errors << 'Name must be lowercase letters, digits, "-" or "."'
            if (!(p.repoUrl ==~ /^(https?:\/\/|git@|ssh:\/\/).+/)) errors << 'Repository URL must be https://, ssh:// or git@'
            if (!p.path && !p.chart) errors << 'Give a path (directory) or a Helm chart name'
            if (!(p.namespace ==~ NAME_RE)) errors << 'Namespace must be lowercase letters, digits, "-"'
            if (!p.server) errors << 'This cluster is not a registered Argo CD destination'
            if (errors) return fail("Not created: ${errors.join('; ')}.")
            Map source = [repoURL: p.repoUrl, targetRevision: p.revision ?: 'HEAD']
            if (p.chart) source.chart = p.chart else source.path = p.path
            Map syncPolicy = [syncOptions: p.createNamespace == 'true' ? ['CreateNamespace=true'] : []]
            if (p.autoSync == 'true') syncPolicy.automated = [prune: p.autoPrune == 'true', selfHeal: p.selfHeal == 'true']
            Map app = [metadata: [name: p.app, annotations: ['morpheus.argocd/created-by': model.user?.username ?: 'unknown']],
                       spec    : [project: p.project ?: 'default', source: source,
                                  destination: [server: p.server, namespace: p.namespace], syncPolicy: syncPolicy]]
            ArgoResult r = c.createApplication(app)
            if (!r.ok) return r
            PanelState.update(model.user?.id, p.clusterId as Long) { PanelState.State s -> s.selectedApp = p.app; s.selectedRef = null }
            ok("Created ${p.app}.${p.autoSync == 'true' ? ' Auto-sync will deploy it.' : ' Press Sync to deploy it.'}")
        }
    }

    /** Per-node actions from the resource tree: restart / sync / delete one resource. */
    def resourceAction(ViewModel<Map> model) {
        act(model, true) { ArgoCdClient c, Map p ->
            Map ref = [kind: p.kind, name: p.name, namespace: p.namespace ?: '', group: p.group ?: '', version: p.version ?: '']
            String label = "${p.kind} ${p.name}"
            switch (p.action) {
                case 'restart':
                    if (!ResourceTree.restartable(p.kind)) return fail("${p.kind} cannot be restarted.")
                    ArgoResult r = c.runResourceAction(p.app, ref, 'restart')
                    return r.ok ? ok("Restart requested for ${label}.") : r
                case 'sync':
                    ArgoResult r = c.syncResource(p.app, ref)
                    return r.ok ? ok("Sync started for ${label}.") : r
                case 'delete':
                    if (p.confirm != p.name) return fail("Type the resource name exactly to delete it.")
                    ArgoResult r = c.deleteResource(p.app, ref, false)
                    if (!r.ok) return r
                    PanelState.update(model.user?.id, p.clusterId as Long) { PanelState.State s -> s.selectedRef = null; s.selectedResource = null }
                    return ok("Deleted ${label}. Argo CD will show it OutOfSync/Missing until the next sync.")
                default:
                    return fail("Unknown action ${p.action}.")
            }
        }
    }

    // ---------- plumbing ----------

    private Object act(ViewModel<Map> model, boolean mutating, Closure body) {
        Map p = params(model)
        Long clusterId = p.clusterId as Long
        User user = model.user
        if (!clusterId) return HTMLResponse.error('clusterId is required', 400)
        if (!'POST'.equalsIgnoreCase(model.request?.method as String)) {
            return deny(model, clusterId, 'That action must be submitted from the Argo CD panel.')
        }
        if (!Access.canRead(user)) {
            return deny(model, clusterId, 'Your Morpheus role has no access to Argo CD applications.')
        }
        if (mutating && !Access.canManage(user)) {
            return deny(model, clusterId, 'Your Morpheus role has read-only access to Argo CD applications.')
        }
        if (p.app != null && !(p.app ==~ NAME_RE)) return deny(model, clusterId, 'Invalid application name.')
        ArgoCdSettings s = ArgoCdSettings.read(morpheus, plugin)
        if (!s.configured) return deny(model, clusterId, 'Argo CD is not configured.')
        ArgoCdClient client = ArgoCdClient.of(s)
        String denied = checkScope(clusterId, s, client, p)
        if (denied) return deny(model, clusterId, denied)
        Object out
        try {
            out = body.call(client, p)
        } catch (Throwable t) {
            log.error("ArgoCD: action failed", t)
            out = fail("Action failed: ${t.class.simpleName}: ${t.message}")
        }
        if (out instanceof ArgoResult) out = fail(((ArgoResult) out).describe())
        Map o = out as Map
        // keep the same app (and node) open after the redirect — the view is seamless across actions
        if (p.app && o.keepOpen != false) {
            PanelState.update(user?.id, clusterId) { PanelState.State st ->
                st.selectedApp = p.app
                st.selectedResource = (p.kind && p.name && p.action != 'delete') ? ResourceTree.key(p.kind, p.namespace, p.name) : null
            }
        }
        log.info("ArgoCD: ${model.user?.username} ${model.request?.requestURI} app=${p.app} -> ${o.level}: ${o.message}")
        flashBack(user, clusterId, o.level as String, o.message as String, model)
    }

    /** The app (or new app's destination) must belong to this cluster; never trust the form for that. */
    private String checkScope(Long clusterId, ArgoCdSettings s, ArgoCdClient client, Map p) {
        ClusterScope scope = ClusterScope.of(morpheus, clusterId)
        if (!scope?.kubernetes) return 'Unknown Kubernetes cluster.'
        ArgoResult list = client.listApplications()
        if (!list.ok) return list.describe()
        List<ArgoApp> all = ((list.data?.items ?: []) as List).collect { ArgoApp.parse(it as Map) }
        List<ClusterMatcher.Match> matches = scope.matches(s, client, all)
        if (p.server != null && !matches.any { it.server == ClusterMatcher.norm(p.server as String) }) {
            return 'That destination is not this cluster in Argo CD.'
        }
        if (p.app && p.server == null) {
            ArgoApp a = all.find { it.name == p.app }
            if (!a) return "Application ${p.app} was not found in Argo CD."
            if (!ClusterMatcher.targets(a, matches)) return "Application ${p.app} does not deploy to this cluster."
        }
        null
    }

    /** Refused actions are logged too, so the audit trail shows attempts as well as changes. */
    private HTMLResponse deny(ViewModel<Map> model, Long clusterId, String why) {
        Map p = params(model)
        log.warn("ArgoCD: DENIED ${model.user?.username} ${model.request?.requestURI} cluster=${clusterId} app=${p.app} -> ${why}")
        flashBack(model.user, clusterId, 'danger', why, model)
    }

    private static Map ok(String m) { [level: 'success', message: m] }

    private static Map fail(String m) { [level: 'danger', message: m] }

    private HTMLResponse flashBack(User user, Long clusterId, String level, String message, ViewModel model = null) {
        PanelState.update(user?.id, clusterId) { PanelState.State s -> s.flashLevel = level; s.flashMessage = message }
        back(clusterId, 'top', model)
    }

    /** Back to the cluster's Argo CD tab: a real 302 when the response allows it, else a script-free meta refresh. */
    private static HTMLResponse back(Long clusterId, String anchor, ViewModel model = null) {
        String url = "/infrastructure/clusters/${clusterId}#!argocd-cluster-tab"
        try {
            def resp = model?.response
            if (resp != null && resp.respondsTo('sendRedirect') && !resp.committed) {
                resp.sendRedirect(url)
                return HTMLResponse.success('')
            }
        } catch (Throwable ignored) {}
        HTMLResponse.success("<!doctype html><html><head><meta http-equiv=\"refresh\" content=\"0;url=${url}\">" +
            "<title>Argo CD</title></head><body><a href=\"${url}\">Back to Argo CD</a></body></html>")
    }

    private static Map params(ViewModel<Map> model) {
        Map out = [:]
        try {
            model.request?.parameterMap?.each { k, v -> out[k as String] = ((v as List)?.first() as String)?.trim() }
        } catch (Throwable ignored) {}
        out
    }
}
