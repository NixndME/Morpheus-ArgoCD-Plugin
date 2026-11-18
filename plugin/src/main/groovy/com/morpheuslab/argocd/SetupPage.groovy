package com.morpheuslab.argocd

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import groovy.util.logging.Slf4j

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Everything the Argo CD integration page shows (Administration > Integrations > Argo CD). */
@Slf4j
class SetupPage {

    static final List<String> BUILDING = ['provisioning', 'initializing', 'syncing', 'pending']
    /** A hung cluster API must not stall the page: short reads here, the background jobs keep the long ones. */
    static final int READ_MILLIS = 8_000
    static final int ROW_SECONDS = 30
    static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern('HH:mm:ss').withZone(ZoneOffset.UTC)

    Long integrationId
    boolean admin
    boolean accessUnknown
    Map flash
    Map csrf
    String sub = 'clusters'
    String bundled
    String error
    ArgoCdSettings settings = new ArgoCdSettings()
    Map connection = [:]
    List<Map> clusters = []
    Map adminLogin

    boolean getHasFlash() { flash != null }
    String getFlashLevel() { flash?.level }
    String getFlashText() { flash?.text }
    String getCsrfParam() { csrf?.param }
    String getCsrfToken() { csrf?.value }
    boolean getSubClusters() { sub == 'clusters' }
    boolean getSubConnection() { sub == 'connection' }
    boolean getSubActivity() { sub == 'activity' }
    boolean getHasClusters() { !clusters.isEmpty() }
    int getClusterCount() { clusters.size() }
    int getInstalledCount() { clusters.count { it.state == 'ours' } as int }
    boolean getConnected() { connection.chip == 'ok' }
    String getConnectedText() { connected ? 'Yes' : 'No' }
    String getSettingsUrl() { settings?.url ?: '' }
    String getDefaultProject() { settings?.defaultProject ?: 'default' }
    boolean getVerifyTls() { settings?.verifyTls }
    boolean getRunning() { Activity.anyRunning }
    boolean getHasAdminLogin() { adminLogin != null }
    boolean getCanInstallAny() { clusters.any { it.canInstall } }
    boolean getHasLog() { !Activity.lines(1).isEmpty() }
    String getLogText() {
        Activity.lines(80).collect { "${CLOCK.format(Instant.ofEpochMilli(it.at as long))}  ${it.cluster}  ${it.text}" }.join('\n')
    }

    // ---------- pure rules (unit tested) ----------

    /** One cluster row: status chip and the buttons that make sense right now. */
    static Map row(Map c, Map st, String job, String connectedUrl, Long me = null) {
        // an install made for another Argo CD integration is shown here but handled on its own page
        boolean mine = st.owner == null || me == null || st.owner == me
        boolean inUse = usesThis(connectedUrl, st, ((c.hosts ?: []) + [c.host]).findAll() as List<String>)
        Map r = [id: c.id, name: c.name, url: "/infrastructure/clusters/${c.id}#!argocd-cluster-tab".toString(),
                 state: st.state, version: st.version ?: '', inUse: inUse, detail: null,
                 confirmPattern: confirmPattern(c.name as String),
                 canInstall: st.state == 'none' && !job && !connectedUrl,
                 canRemove: st.state == 'ours' && !job && mine,
                 canConnect: st.state == 'ours' && st.ready && !job && !inUse && mine]
        switch (st.state) {
            case 'none': r.status = 'Not installed'; r.chip = 'unknown'; break
            case 'ours':
                r.status = st.ready ? 'Installed' : 'Starting'; r.chip = st.ready ? 'ok' : 'info'
                if (!mine) r.detail = 'Installed for another Argo CD integration'
                break
            case 'other':
                r.status = 'Argo CD found'; r.chip = 'info'
                r.detail = "In namespace ${st.namespace}, not installed by this plugin".toString()
                break
            default:
                boolean building = (c.clusterStatus as String) in BUILDING
                r.status = building ? 'Being built' : 'Cannot reach'
                r.chip = building ? 'info' : 'bad'
                if (!building) r.detail = st.error
        }
        if (job) { r.status = job; r.chip = 'info' }
        r
    }

    /** What the plugin is connected to, in one line. */
    static Map connection(ArgoCdSettings s, ArgoResult version, ArgoResult who) {
        if (!s?.configured) return [chip: 'unknown', status: 'Not set', text: 'The plugin is not connected to an Argo CD yet.']
        if (!version?.ok) return [chip: 'bad', status: 'Cannot reach', text: "${s.url}: ${version?.describe() ?: 'no answer'}".toString()]
        if (!loggedIn(who)) return [chip: 'bad', status: 'Token rejected', text: "${s.url}: ${who?.ok ? 'Argo CD does not accept the token' : (who?.describe() ?: 'no answer')}".toString()]
        String v = (version.data?.Version ?: '') as String
        if (v.contains('+')) v = v.substring(0, v.indexOf('+'))
        [chip: 'ok', status: 'Connected', text: "${s.url}, Argo CD ${v}, as ${who.data?.username ?: who.data?.iss}".toString()]
    }

    /** True when the plugin's saved address is the Argo CD on this cluster (any node or the API host). */
    static boolean usesThis(String connectedUrl, Map st, List<String> clusterHosts) {
        if (!connectedUrl || !(st.state in ['ours', 'other'])) return false
        String saved = connectedUrl.replaceAll('/+$', '')
        (st.url && (st.url as String).replaceAll('/+$', '') == saved) || host(saved) in clusterHosts
    }

    /** Node IPs Morpheus knows for the cluster: workers first, external before internal. */
    static List<String> nodeHosts(MorpheusContext morpheus, ComputeServerGroup c) {
        try {
            List<ComputeServer> servers = morpheus.services.computeServer.list(new DataQuery().withFilter('serverGroup.id', c.id))
            servers.sort(false) { ComputeServer s -> ((s.computeServerType?.code ?: '') as String).toLowerCase().contains('master') ? 1 : 0 }
                .collectMany { ComputeServer s -> [s.externalIp, s.internalIp] }.findAll().collect { it as String }.unique()
        } catch (Throwable t) {
            log.warn("ArgoCD: could not list the nodes of cluster ${c.id}: ${t}")
            []
        }
    }

    /** The name as an HTML pattern: regex syntax characters escaped, nothing else (browsers compile it in unicode mode). */
    static String confirmPattern(String name) {
        (name ?: '').replaceAll(/[\^$\\.*+?()\[\]{}|\/]/) { String ch -> '\\' + ch }
    }

    /** An install whose integration was deleted (for example with the plugin) has no owner any more. */
    static Map liveOwner(Map st, Collection<Long> integrationIds) {
        st.owner && !(st.owner in integrationIds) ? st + [owner: null] : st
    }

    /** Argo CD answers userinfo with 200 for any token; only loggedIn true means it accepted it. */
    static boolean loggedIn(ArgoResult who) { who?.ok && who.data instanceof Map && who.data.loggedIn == true }

    static String host(String url) {
        try { new URI(url).host } catch (Exception ignored) { null }
    }

    // ---------- the live page ----------

    static SetupPage build(MorpheusContext morpheus, Plugin plugin, AccountIntegration integration) {
        SetupPage v = new SetupPage(integrationId: integration?.id, csrf: Csrf.token(), bundled: Installer.bundledVersion())
        v.flash = Flash.take()
        if (v.flash?.sub) v.sub = v.flash.sub
        SelfApi api = SelfApi.capture()
        String access = api?.permission(ArgoCdPlugin.PERMISSION_ADMIN)
        if (access == null) {
            v.error = 'Could not check your access with Morpheus. Reload the page.'
            v.accessUnknown = true
            return v
        }
        v.admin = access == 'full'
        if (!v.admin) return v
        try {
            v.settings = connectionOf(morpheus, plugin, integration, api, v)
            if (v.settings.configured) {
                ArgoCdClient client = ArgoCdClient.of(v.settings)
                ArgoResult ver = client.version()
                v.connection = connection(v.settings, ver, ver.ok ? client.userInfo() : null)
            } else {
                v.connection = connection(v.settings, null, null)
            }
            Set<Long> visible = api.visibleClusterIds()
            List<ComputeServerGroup> all = morpheus.services.cluster.list(new DataQuery()).findAll { AdminAccess.isHks(it) && it.id in visible }
            // ask every cluster at the same time, so the page waits for the slowest one only
            def pool = Executors.newFixedThreadPool(Math.max(1, Math.min(8, all.size())))
            try {
                List<ComputeServerGroup> sorted = all.sort { it.name?.toLowerCase() }
                List futures = sorted.collect { ComputeServerGroup c -> pool.submit({ clusterRow(morpheus, c, v) } as Callable) }
                futures.eachWithIndex { f, int i ->
                    ComputeServerGroup c = sorted[i]
                    try {
                        v.clusters << (Map) f.get(ROW_SECONDS, TimeUnit.SECONDS)
                    } catch (Throwable t) {
                        log.warn("ArgoCD: setup row for cluster ${c.id} failed: ${t}")
                        v.clusters << row([id: c.id, name: c.name, host: host(c.serviceUrl), clusterStatus: c.status?.toString()],
                            [state: 'error', error: 'No answer from the cluster in time'], Activity.running(c.id), null)
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        } catch (Throwable t) {
            log.warn("ArgoCD: setup page failed: ${t}", t)
            v.error = "The page could not load everything: ${t.message}"
        }
        v
    }

    /** This integration's connection. The first page opened takes over the old plugin settings. */
    static ArgoCdSettings connectionOf(MorpheusContext morpheus, Plugin plugin, AccountIntegration integration, SelfApi api, SetupPage v) {
        if (!integration) return new ArgoCdSettings()
        Map conn = Instances.connection(morpheus, integration)
        ArgoCdSettings old = ArgoCdSettings.read(morpheus, plugin)
        // only a main tenant integration may take over the old plugin wide connection; read fresh data first
        if (!conn && old.configured && integration.account?.masterAccount) Instances.forget()
        if (!conn && old.configured && integration.account?.masterAccount && !Instances.all(morpheus, plugin).any { it.id != Instances.LEGACY_ID && it.url == old.url }) {
            conn = [url: old.url, token: old.token, verifyTls: old.verifyTls, project: old.defaultProject]
            String err = Instances.save(morpheus, integration, conn) ?: api?.saveSettings(ArgoCdSetupController.config(null, null, old.verifyTls, old.defaultProject))
            v.flash = err ? [level: 'warning', text: err] : [level: 'info', text: "The connection from the plugin settings (${old.url}) is now kept here.".toString()]
            Instances.forget()
        }
        conn ? Instances.instance(integration.id, integration.name, null, true, conn).settings() : new ArgoCdSettings()
    }

    private static Map clusterRow(MorpheusContext morpheus, ComputeServerGroup c, SetupPage v) {
        Kube kube = Kube.of(morpheus, c)
        kube.transport = { String m, String url, String body, String ctype, String bearer -> Kube.send(m, url, body, ctype, bearer, READ_MILLIS) }
        Map st = kube.usable ? liveOwner(Installer.status(kube), Instances.integrations(morpheus)*.id) : [state: 'error', error: 'Morpheus has no API access for this cluster yet']
        Map info = [id: c.id, name: c.name, host: host(c.serviceUrl), hosts: nodeHosts(morpheus, c), clusterStatus: c.status?.toString()]
        Map r = row(info, st, Activity.running(c.id), v.settings.configured ? v.settings.url : null, v.integrationId)
        if (r.inUse && st.state == 'ours') {
            v.adminLogin = [url: st.url ?: v.settings.url, user: 'admin', password: Installer.adminPassword(kube), cluster: c.name]
        }
        r
    }
}
