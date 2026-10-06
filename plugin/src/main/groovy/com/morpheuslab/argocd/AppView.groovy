package com.morpheuslab.argocd

import groovy.json.JsonOutput

/**
 * One application's full detail view, pre-rendered so opening the app, closing it and clicking tree
 * nodes are instant CSS toggles (radio inputs) — no page reload, no JavaScript. Only real actions
 * (sync, delete, restart, create) go to the server.
 */
class AppView {

    static final int MAX_MANIFESTS = 40
    static final int MAX_POD_LOGS = 8
    static final int LOG_TAIL = 80

    int index
    ArgoApp app
    boolean open
    String treeHtml
    String treeMessage
    ResourceTree tree
    List<NodeCard> nodes = []
    Integer selectedNode
    String css = ''

    String getPrefix() { "argocd-a${index}" }
    String getRadioId() { "argocd-app-${index}" }
    boolean getHasTree() { treeHtml != null }
    List<Map> getZoomOptions() {
        String d = tree && tree.width > 1020 ? 'fit' : '100'
        ([[id: 'fit', label: 'Fit']] + ResourceTree.ZOOM_LEVELS.collect { [id: "${it}".toString(), label: "${it}%".toString()] })
            .collect { it + [checked: it.id == d, radio: "${prefix}-z-${it.id}".toString()] }
    }
    List<Map> getNodeRadios() {
        [[id: "${prefix}-n-none".toString(), checked: selectedNode == null]] +
            nodes.collect { [id: "${prefix}-n${it.index}".toString(), checked: selectedNode == it.index] }
    }

    static AppView build(int index, ArgoApp app, ArgoCdClient client, String selectedResourceKey, boolean open) {
        AppView v = new AppView(index: index, app: app, open: open)
        ArgoResult treeRes = client.resourceTree(app.name)
        if (!treeRes.ok) {
            v.treeMessage = treeRes.describe()
            return v
        }
        v.tree = ResourceTree.build(app, treeRes.data as Map, null, null)
        v.treeHtml = v.tree.toHtml(v.prefix)

        List<Map> events = []
        ArgoResult ev = client.events(app.name)
        if (ev.ok) events = ((ev.data?.items ?: []) as List<Map>)

        int manifests = 0, logs = 0
        v.tree.all.each { ResourceTree.Node n ->
            NodeCard c = new NodeCard(index: n.index, kind: n.kind, name: n.name, namespace: n.namespace ?: '',
                group: n.group ?: '', version: n.version ?: '', health: n.health ?: '', sync: n.sync ?: '',
                infoItems: n.infoItems, images: n.images, isApp: n.uid == 'app', appName: app.name)
            if (!c.isApp) {
                if (manifests < MAX_MANIFESTS) {
                    // per-resource events, exactly what the Argo CD UI shows on a resource's EVENTS tab
                    ArgoResult re = client.events(app.name, [kind: n.kind, name: n.name, namespace: n.namespace, uid: n.uid])
                    List<Map> items = re.ok ? ((re.data?.items ?: []) as List<Map>) : events.findAll { Map e -> e.involvedObject?.kind == n.kind && e.involvedObject?.name == n.name }
                    c.events = items.sort { a, b -> ((b.lastTimestamp ?: b.eventTime ?: '') as String) <=> ((a.lastTimestamp ?: a.eventTime ?: '') as String) }
                        .take(10).collect { Map e -> eventRow(e) }
                }
                if (manifests < MAX_MANIFESTS) {
                    manifests++
                    ArgoResult r = client.resource(app.name, [kind: n.kind, name: n.name, namespace: n.namespace, group: n.group, version: n.version])
                    Object m = r.ok && r.data instanceof Map ? r.data.manifest : null
                    c.manifest = m instanceof String ? ClusterPanel.pretty(m as String) : (r.ok ? null : r.describe())
                }
                if (n.kind == 'Pod' && logs < MAX_POD_LOGS) {
                    logs++
                    ArgoResult lr = client.podLogs(app.name, [kind: 'Pod', name: n.name, namespace: n.namespace], LOG_TAIL)
                    c.logs = lr.ok ? ((lr.data as List<String>) ?: ['(no log lines yet)']).join('\n') : lr.describe()
                }
            } else {
                c.events = events.findAll { Map e -> e.involvedObject?.kind == 'Application' }.take(10).collect { eventRow(it) }
            }
            v.nodes << c
            if (selectedResourceKey && !c.isApp &&
                (selectedResourceKey == n.uid || selectedResourceKey == ResourceTree.key(n.kind, n.namespace, n.name))) {
                v.selectedNode = n.index
            }
        }
        v.css = v.buildCss()
        return v
    }

    private String buildCss() {
        String scope = ".argocd-appview-${index}"
        StringBuilder css = new StringBuilder(tree.positionCss(prefix, scope))
        css << "#${prefix}-z-fit:checked~.argocd-graph .argocd-stage{zoom:${tree.fitZoom}}"
        ResourceTree.ZOOM_LEVELS.each { int z ->
            css << "#${prefix}-z-${z}:checked~.argocd-graph .argocd-stage{zoom:${z / 100}}"
        }
        ((['fit'] + ResourceTree.ZOOM_LEVELS.collect { "${it}".toString() }) as List<String>).each { String id ->
            css << "#${prefix}-z-${id}:checked~.argocd-zoombar label[for=${prefix}-z-${id}]{background:var(--argocd-accent);border-color:var(--argocd-accent);color:#fff}"
        }
        [125, 150, 200].each { css << "#${prefix}-z-${it}:checked~.argocd-graph .argocd-node-detail{display:block}" }
        css.toString()
    }

    private static Map eventRow(Map e) {
        [type: e.type, reason: e.reason, message: e.message, count: e.count ?: 1,
         ago: ArgoApp.ago((e.lastTimestamp ?: e.eventTime ?: e.firstTimestamp) as String) ?: '']
    }
}

class NodeCard {
    int index
    String appName
    String kind, name, namespace, group, version, health, sync
    boolean isApp
    List<Map> infoItems = []
    List<String> images = []
    List<Map> events = []
    String manifest
    String logs

    String getHealthClass() { ArgoApp.chip(health == 'Healthy' ? 'ok' : health == 'Progressing' ? 'info' : health in ['Degraded', 'Missing'] ? 'bad' : health == 'Suspended' ? 'warn' : 'unknown') }
    String getSyncClass() { ArgoApp.chip(sync == 'Synced' ? 'ok' : sync == 'OutOfSync' ? 'warn' : 'unknown') }
    boolean getHasHealth() { health }
    boolean getHasSync() { sync }
    boolean getHasEvents() { !events.isEmpty() }
    boolean getHasImages() { !images.isEmpty() }
    boolean getHasInfo() { !infoItems.isEmpty() }
    boolean getIsPod() { kind == 'Pod' }
    boolean getRestartable() { ResourceTree.restartable(kind) }
    String getConfirmPattern() { (name ?: '').replace('.', '\\.') }
    String getImageList() { images.join(', ') }
}
