package com.morpheuslab.argocd

import com.morpheuslab.argocd.ClusterMatcher.Match
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

/**
 * View model for the cluster "Argo CD" tab. Built in Groovy because Handlebars cannot compute; it
 * the template root IS model.object — reference {{field}}, never {{object.field}}.
 */
@Slf4j
class ClusterPanel {

    static final String IN_CLUSTER = 'https://kubernetes.default.svc'

    // context
    Long clusterId
    String clusterName
    String clusterApiUrl
    String tabUrl
    boolean canRead
    boolean canManage
    String accessLevel
    String csrfParam
    String csrfToken
    Map flash

    // connection
    String state = 'ok'              // notConfigured | unreachable | error | ok
    String stateMessage
    String argocdUrl
    String argocdVersion
    String argocdUser

    // destination resolution (ClusterMatcher)
    List<Match> matches = []
    List<String> destinations = []
    String newAppServer
    List<Map> newAppServers = []
    String newAppBlockedReason
    boolean argoRunsHere

    // apps
    List<ArgoApp> apps = []
    int otherClusterApps
    List<String> projects = []
    String defaultProject

    // pre-rendered app views (instant open/close, no reload)
    static final int MAX_PRERENDER = 15
    List<AppView> appViews = []
    String panelCss = ''

    // selection
    ArgoApp selected
    String treeSvg
    ResourceTree tree
    List<String> selectedLogs
    String treeMessage
    Map selectedRef
    String selectedManifest
    List<Map> selectedEvents = []
    String selectedMessage

    // ---- getters for the template ----
    boolean getNotConfigured() { state == 'notConfigured' }
    boolean getHasError() { state in ['unreachable', 'error'] }
    boolean getIsOk() { state == 'ok' }
    boolean getHasApps() { !apps.isEmpty() }
    int getTotal() { apps.size() }
    int getSynced() { apps.count { it.syncStatus == 'Synced' } as int }
    int getOutOfSync() { apps.count { it.syncStatus == 'OutOfSync' } as int }
    int getHealthy() { apps.count { it.healthStatus == 'Healthy' } as int }
    int getProgressing() { apps.count { it.healthStatus == 'Progressing' } as int }
    int getDegraded() { apps.count { it.healthStatus in ['Degraded', 'Missing'] } as int }
    boolean getHasSelected() { selected != null }
    boolean getNoAppOpen() { !appViews.any { it.open } }
    boolean getHasSelectedResource() { selectedRef != null }
    String getSelectedKind() { selectedRef?.kind }
    String getSelectedName() { selectedRef?.name }
    String getSelectedNamespace() { selectedRef?.namespace }
    String getSelectedGroup() { selectedRef?.group ?: '' }
    String getSelectedVersion() { selectedRef?.version ?: '' }
    String getSelectedUid() { selectedRef?.uid ?: '' }
    boolean getSelectedRestartable() { canManage && ResourceTree.restartable(selectedRef?.kind as String) }
    boolean getSelectedIsPod() { selectedRef?.kind == 'Pod' }
    boolean getHasLogs() { selectedLogs != null }
    String getLogText() { selectedLogs ? selectedLogs.join('\n') : '(no log lines)' }
    boolean getHasEvents() { !selectedEvents.isEmpty() }
    String getZoomCss() { tree?.zoomCss() ?: '' }
    List<Map> getZoomOptions() { tree?.zoomOptions ?: [] }
    boolean getCanCreate() { canManage && newAppServer != null }
    /** e.g. "Managed by Argo CD as in-cluster and hks-external (verified by resource UID)" */
    String getMatchText() {
        if (!matches) return ''
        List<String> names = matches.collect { it.name ?: it.server }
        String how = matches*.methodText.unique().join(', ')
        "Managed by Argo CD as ${names.size() > 1 ? names[0..-2].join(', ') + ' and ' + names[-1] : names[0]} (${how})"
    }
    String getFlashLevel() { flash?.level }
    String getFlashMessage() { flash?.message }
    boolean getHasFlash() { flash?.message }

    static ClusterPanel build(Map ctx) {
        ClusterPanel p = new ClusterPanel(
            clusterId: ctx.clusterId as Long, clusterName: ctx.clusterName, clusterApiUrl: ctx.clusterApiUrl,
            tabUrl: "/infrastructure/clusters/${ctx.clusterId}#!argocd-cluster-tab",
            accessLevel: ctx.accessLevel, canRead: ctx.accessLevel in ['read', 'full'], canManage: ctx.accessLevel == 'full',
            csrfParam: ctx.csrf?.param, csrfToken: ctx.csrf?.value, flash: ctx.flash)
        ArgoCdSettings s = ctx.settings as ArgoCdSettings
        p.defaultProject = s.defaultProject ?: 'default'
        if (!s.configured) {
            p.state = 'notConfigured'
            return p
        }
        p.argocdUrl = s.url
        ArgoCdClient client = (ctx.client ?: ArgoCdClient.of(s)) as ArgoCdClient

        ArgoResult version = client.version()
        if (!version.ok) return p.fail(version)
        p.argocdVersion = (version.data instanceof Map) ? version.data.Version : null

        ArgoResult list = client.listApplications()
        if (!list.ok) return p.fail(list)
        ArgoResult who = client.userInfo()
        p.argocdUser = who.ok ? (who.data?.username ?: who.data?.iss) : null

        List<Map> argoClusters = argoClusters(client)
        List<ArgoApp> all = ((list.data?.items ?: []) as List).collect { ArgoApp.parse(it as Map) }
        ClusterMatcher matcher = new ClusterMatcher(clusterApiUrl: p.clusterApiUrl, argoUrl: s.url, client: client,
            clusterHosts: (ctx.clusterHosts ?: []) as List<String>, probe: ctx.probe as K8sProbe)
        p.matches = ctx.cached ? matcher.cachedMatch(p.clusterId, argoClusters, all) : matcher.match(argoClusters, all)
        p.applyMatches(argoClusters)
        p.apps = all.findAll { a -> ClusterMatcher.targets(a, p.matches) }.sort { it.name }
        p.otherClusterApps = all.size() - p.apps.size()

        ArgoResult projects = client.listProjects()
        p.projects = projects.ok ? ((projects.data?.items ?: []) as List).collect { it.metadata?.name as String }.sort() : [p.defaultProject]
        if (!p.projects.contains(p.defaultProject)) p.projects.add(0, p.defaultProject)

        PanelState.State st = ctx.state as PanelState.State
        List<ArgoApp> toRender = p.apps.take(MAX_PRERENDER)
        ArgoApp sel = p.apps.find { it.name == st?.selectedApp }
        if (sel && !toRender.contains(sel)) toRender << sel
        toRender.eachWithIndex { ArgoApp a, int i ->
            a.viewIndex = i
            boolean isSel = a.name == st?.selectedApp
            p.appViews << AppView.build(i, a, client, isSel ? st.selectedResource : null, isSel)
        }
        p.panelCss = p.appViews.collect { AppView v ->
            "#${v.radioId}:checked~.argocd-appviews .argocd-appview-${v.index}{display:block}" +
                "#${v.radioId}:checked~.argocd-list .argocd-open-${v.index}{display:none}" +
                "#${v.radioId}:checked~.argocd-list .argocd-shut-${v.index}{display:block}" +
                "#${v.radioId}:checked~.argocd-list .argocd-row-${v.index} td{background:rgba(47,127,193,.12)}" + v.css
        }.join('')
        return p
    }

    private ClusterPanel fail(ArgoResult r) {
        state = r.kind == ArgoResult.Kind.UNREACHABLE ? 'unreachable' : 'error'
        stateMessage = r.describe()
        this
    }

    /** Argo CD's own spelling of the server URL (matching works on lower-cased URLs). */
    private static String rawServer(List<Map> argoClusters, String server) {
        argoClusters.find { norm(it.server as String) == server }?.server ?: server
    }

    static List<Map> argoClusters(ArgoCdClient client) {
        ArgoResult r = client.listClusters()
        r.ok ? ((r.data?.items ?: []) as List<Map>) : []
    }

    /** Turn proved matches into the destinations this tab shows and deploys to. */
    private void applyMatches(List<Map> argoClusters) {
        destinations = matches*.server
        argoRunsHere = destinations.contains(norm(IN_CLUSTER))
        // deploy where most apps already go; in-cluster wins a tie
        Match best = matches.sort(false) { Match a, Match b ->
            (b.apps <=> a.apps) ?: ((a.server == norm(IN_CLUSTER) ? 0 : 1) <=> (b.server == norm(IN_CLUSTER) ? 0 : 1))
        }.find()
        if (best) newAppServer = rawServer(argoClusters, best.server)
        newAppServers = matches.collect { Match m ->
            String raw = rawServer(argoClusters, m.server)
            [server: raw, label: m.name ? "${m.name} (${raw})".toString() : raw, selected: raw == newAppServer]
        }
        if (!best) newAppBlockedReason = 'Argo CD does not manage any application on this cluster.'
    }

    private void loadSelection(ArgoCdClient client, PanelState.State st) {
        selected = apps.find { it.name == st.selectedApp }
        if (!selected) return
        ArgoResult treeRes = client.resourceTree(selected.name)
        if (treeRes.ok) {
            String clusterIdStr = clusterId as String
            ResourceTree t = ResourceTree.build(selected, treeRes.data as Map, { ResourceTree.Node n ->
                '/plugin/argocd/select?' + [clusterId: clusterIdStr, app: selected.name, kind: n.kind, name: n.name,
                    namespace: n.namespace ?: '', group: n.group ?: '', version: n.version ?: '', uid: n.uid ?: '']
                    .collect { k, v -> "${k}=${URLEncoder.encode(v as String, 'UTF-8')}" }.join('&')
            }, st.selectedResource)
            this.tree = t
            treeSvg = t.toSvg()
        } else {
            treeMessage = treeRes.describe()
        }
        if (st.selectedRef) {
            selectedRef = st.selectedRef
            ArgoResult res = client.resource(selected.name, st.selectedRef)
            if (res.ok) {
                Object manifest = (res.data instanceof Map) ? res.data.manifest : null
                selectedManifest = manifest instanceof String ? pretty(manifest as String) : JsonOutput.prettyPrint(JsonOutput.toJson(res.data))
            } else {
                selectedMessage = res.describe()
            }
            if (st.selectedRef.kind == 'Pod') {
                ArgoResult logs = client.podLogs(selected.name, st.selectedRef, 200)
                selectedLogs = logs.ok ? (logs.data as List<String>).takeRight(200) : ["(${logs.describe()})".toString()]
            }
            ArgoResult ev = client.events(selected.name, st.selectedRef)
            if (ev.ok) {
                selectedEvents = ((ev.data?.items ?: []) as List).takeRight(15).reverse().collect { Map e ->
                    [type: e.type, reason: e.reason, message: e.message, count: e.count ?: 1,
                     ago: ArgoApp.ago((e.lastTimestamp ?: e.eventTime ?: e.firstTimestamp) as String) ?: '']
                }
            }
        }
    }

    /** Like Argo CD's manifest view: hide managedFields and the last-applied annotation (noise, and very wide). */
    static String pretty(String json) {
        try {
            Object doc = new groovy.json.JsonSlurper().parseText(json)
            if (doc instanceof Map && doc.metadata instanceof Map) {
                Map md = doc.metadata as Map
                md.remove('managedFields')
                if (md.annotations instanceof Map) {
                    (md.annotations as Map).remove('kubectl.kubernetes.io/last-applied-configuration')
                    if ((md.annotations as Map).isEmpty()) md.remove('annotations')
                }
            }
            JsonOutput.prettyPrint(JsonOutput.toJson(doc))
        } catch (Exception ignored) { json }
    }

    static String norm(String url) { url ? url.trim().replaceAll('/+$', '').toLowerCase() : null }

    static String host(String url) {
        try { url ? new URI(url.trim()).host : null } catch (Exception ignored) { null }
    }
}
