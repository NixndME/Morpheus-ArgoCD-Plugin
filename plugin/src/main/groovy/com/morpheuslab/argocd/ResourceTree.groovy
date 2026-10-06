package com.morpheuslab.argocd

/**
 * Argo CD's application resource graph — Application -> managed resources -> owned children
 * (Deployment -> ReplicaSet -> Pod, Service -> EndpointSlice, …) — laid out the way the Argo CD UI
 * draws it (left to right, children grouped beside their parent) and rendered server-side as inline
 * SVG. No JavaScript: every node is an SVG link that selects it in the panel.
 */
class ResourceTree {

    static final int NODE_W = 260
    static final int NODE_H = 66
    static final int COL_GAP = 64
    static final int ROW_GAP = 14
    static final int PAD = 12

    static class Node {
        String uid, kind, name, namespace, group, version, health, sync, info, detail
        int restarts
        boolean failed
        int index
        List<Map> infoItems = []
        List<String> images = []
        String createdAt
        List<Node> children = []
        int depth
        double y
        boolean selected
        String href
    }

    Node root
    List<Node> all = []
    int width
    int height

    /**
     * @param app      parsed application (for root status + per-resource sync status)
     * @param treeData raw GET /api/v1/applications/{name}/resource-tree response
     * @param hrefFor  builds the select-link for a node (null = not clickable)
     * @param selectedUid uid (or kind/ns/name key) of the selected node, highlighted
     */
    static ResourceTree build(ArgoApp app, Map treeData, Closure<String> hrefFor, String selectedKey) {
        ResourceTree t = new ResourceTree()
        Map<String, String> syncByKey = [:]
        app.resources.each { syncByKey[key(it.kind, it.namespace, it.name)] = it.syncStatus }

        t.root = new Node(uid: 'app', kind: 'Application', name: app.name, namespace: app.namespace,
            health: app.healthStatus, sync: app.syncStatus, info: app.shortRevision,
            detail: app.operationPhase ? "last sync ${app.operationPhase}" : '', failed: app.operationFailed)
        Map<String, Node> byUid = [:]
        List rawNodes = (treeData?.nodes ?: []) as List
        rawNodes.each { Map n ->
            Node node = new Node(uid: n.uid ?: key(n.kind, n.namespace, n.name), kind: n.kind, name: n.name,
                namespace: n.namespace, group: n.group ?: '', version: n.version ?: '',
                health: n.health?.status ?: '', sync: syncByKey[key(n.kind, n.namespace, n.name)] ?: '',
                info: infoLine(n), detail: detailLine(n), restarts: restarts(n),
                infoItems: ((n.info ?: []) as List).collect { [name: it.name as String, value: it.value as String] },
                images: ((n.images ?: []) as List).collect { it as String }, createdAt: n.createdAt as String)
            node.failed = node.health in ['Degraded', 'Missing'] || (node.info ?: '') =~ /(?i)error|crashloop|backoff|failed|evicted/
            byUid[node.uid] = node
        }
        rawNodes.each { Map n ->
            Node node = byUid[n.uid ?: key(n.kind, n.namespace, n.name)]
            List parents = (n.parentRefs ?: []) as List
            Node parent = parents.collect { Map p -> byUid[p.uid ?: key(p.kind, p.namespace, p.name)] }.find { it != null }
            (parent ?: t.root).children << node
        }
        // managed resources Argo CD knows about but the live tree lacks (e.g. Missing)
        app.resources.each { ArgoResource r ->
            if (!byUid.values().any { it.kind == r.kind && it.name == r.name && it.namespace == r.namespace }) {
                t.root.children << new Node(uid: key(r.kind, r.namespace, r.name), kind: r.kind, name: r.name,
                    namespace: r.namespace, health: r.healthStatus ?: 'Missing', sync: r.syncStatus, info: '')
            }
        }
        t.layout(hrefFor, selectedKey)
        return t
    }

    private void layout(Closure<String> hrefFor, String selectedKey) {
        int maxDepth = 0
        double nextRow = 0
        Closure place
        place = { Node n, int depth ->
            n.depth = depth
            maxDepth = Math.max(maxDepth, depth)
            n.index = all.size()
            all << n
            n.children.sort { a, b -> (kindOrder(a.kind) <=> kindOrder(b.kind)) ?: (a.name <=> b.name) }
            if (!n.children) {
                n.y = nextRow++
            } else {
                n.children.each { place(it, depth + 1) }
                n.y = (n.children.first().y + n.children.last().y) / 2
            }
            if (n.uid != 'app') {
                n.href = hrefFor ? hrefFor(n) : null
                n.selected = selectedKey && (selectedKey == n.uid || selectedKey == key(n.kind, n.namespace, n.name))
            }
        }
        place(root, 0)
        width = PAD * 2 + (maxDepth + 1) * NODE_W + maxDepth * COL_GAP
        height = PAD * 2 + (int) Math.max(1, nextRow) * (NODE_H + ROW_GAP) - ROW_GAP
    }

    /**
     * HTML rendering for the script-free interactive view: SVG draws only the edges; each node is an HTML
     * <label for="{prefix}-n{index}"> so clicking it checks a radio and CSS reveals that node's detail card.
     * Positions go into the <style> block (positionCss) rather than style attributes.
     */
    String toHtml(String prefix) {
        StringBuilder sb = new StringBuilder()
        sb << "<div class=\"argocd-stage ${prefix}-stage\">"
        sb << "<svg class=\"argocd-edges\" xmlns=\"http://www.w3.org/2000/svg\" width=\"${width}\" height=\"${height}\" viewBox=\"0 0 ${width} ${height}\" aria-hidden=\"true\">"
        all.each { Node p ->
            p.children.each { Node c ->
                int x1 = x(p) + NODE_W, y1 = y(p) + (int) (NODE_H / 2)
                int x2 = x(c), y2 = y(c) + (int) (NODE_H / 2)
                int mx = (int) ((x1 + x2) / 2)
                sb << "<path class=\"argocd-edge\" d=\"M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}\"/>"
            }
        }
        sb << '</svg>'
        all.each { Node n ->
            String cls = "argocd-node argocd-pos-${n.index} argocd-hn-${css(n.health)} argocd-sn-${css(n.sync)}" +
                "${n.uid == 'app' ? ' argocd-node-app' : ''}${n.failed ? ' argocd-node-failed' : ''}"
            String body = nodeBody(n)
            // two labels per node: "on" selects it; "off" (shown only while selected) deselects it — click toggles
            sb << "<label for=\"${prefix}-n${n.index}\" class=\"${cls} argocd-node-on\" title=\"${esc(n.kind)} ${esc(n.namespace ? n.namespace + '/' : '')}${esc(n.name)}\">${body}</label>"
            sb << "<label for=\"${prefix}-n-none\" class=\"${cls} argocd-node-off\" title=\"Hide details\">${body}</label>"
        }
        sb << '</div>'
        sb.toString()
    }

    private static String nodeBody(Node n) {
        StringBuilder sb = new StringBuilder()
        sb << "<span class=\"argocd-kind\">${esc(abbr(n.kind))}</span>"
        sb << "<span class=\"argocd-node-main\"><span class=\"argocd-node-name\">${esc(n.name)}</span>"
        sb << "<span class=\"argocd-node-kind\">${esc(n.kind)}${n.info ? ' · ' + esc(n.info) : ''}</span>"
        if (n.detail) sb << "<span class=\"argocd-node-detail\">${esc(n.detail)}</span>"
        sb << '</span><span class="argocd-node-status">'
        if (n.health) sb << "<span class=\"argocd-t-${css(n.health)}\">${esc(n.health)}</span>"
        if (n.sync) sb << "<span class=\"argocd-t-${css(n.sync)}\">${esc(n.sync)}</span>"
        if (n.restarts > 0) sb << "<span class=\"argocd-t-degraded\">${n.restarts} restart${n.restarts == 1 ? '' : 's'}</span>"
        sb << '</span>'
        sb.toString()
    }

    /** Node positions, stage size, and the per-node selection rules for this app's radios. */
    String positionCss(String prefix, String scope) {
        StringBuilder css = new StringBuilder()
        css << "${scope} .${prefix}-stage{width:${width}px;height:${height}px}"
        all.each { Node n ->
            css << "${scope} .argocd-pos-${n.index}{left:${x(n)}px;top:${y(n)}px}"
            css << "#${prefix}-n${n.index}:checked~.argocd-graph .argocd-pos-${n.index}.argocd-node-on{display:none}"
            css << "#${prefix}-n${n.index}:checked~.argocd-graph .argocd-pos-${n.index}.argocd-node.argocd-node-off{display:flex;border-color:var(--argocd-accent);box-shadow:0 0 0 2px var(--argocd-accent)}"
            css << "#${prefix}-n${n.index}:checked~.argocd-nodecards .argocd-card-${n.index}{display:block}"
        }
        css.toString()
    }

    /** CSS zoom factor that fits the graph into a ~1100px panel. */
    /** Panel canvas is ~1060px wide inside the Morpheus cluster page; leave a margin. */
    String getFitZoom() { width > 1020 ? String.format('%.2f', 1020d / width) : '1' }

    static final List<Integer> ZOOM_LEVELS = [50, 75, 100, 125, 150, 200]

    /** Fit when the graph is wider than a typical panel, otherwise 100%. */
    String getDefaultZoom() { width > 1100 ? 'fit' : '100' }

    /**
     * CSS-only zoom (pane in / pane out): radio inputs precede the canvas; each :checked level sizes the SVG
     * (its viewBox keeps the drawing crisp) and the canvas scrolls to pan. At 125%+ the detail line appears.
     */
    String zoomCss() {
        StringBuilder css = new StringBuilder()
        css << ".argocd-canvas svg{width:${width}px;height:${height}px}"
        css << '#argocd-zoom-fit:checked~.argocd-canvas svg{width:100%;height:auto}'
        ZOOM_LEVELS.each { int z ->
            css << "#argocd-zoom-${z}:checked~.argocd-canvas svg{width:${(int) (width * z / 100)}px;height:${(int) (height * z / 100)}px}"
            css << "#argocd-zoom-${z}:checked~.argocd-zoombar label[for=argocd-zoom-${z}]{background:var(--argocd-accent);color:#fff}"
        }
        css << '#argocd-zoom-fit:checked~.argocd-zoombar label[for=argocd-zoom-fit]{background:var(--argocd-accent);color:#fff}'
        css << '.argocd-node-detail{display:none}'
        [125, 150, 200].each { css << "#argocd-zoom-${it}:checked~.argocd-canvas .argocd-node-detail{display:inline}" }
        css.toString()
    }

    List<Map> getZoomOptions() {
        String d = defaultZoom
        [[id: 'fit', label: 'Fit', checked: d == 'fit']] + ZOOM_LEVELS.collect { [id: "${it}".toString(), label: "${it}%".toString(), checked: d == "${it}".toString()] }
    }

    private static int x(Node n) { PAD + n.depth * (NODE_W + COL_GAP) }

    private static int y(Node n) { PAD + (int) Math.round(n.y * (NODE_H + ROW_GAP)) }

    String toSvg() {
        StringBuilder sb = new StringBuilder()
        sb << "<svg class=\"argocd-tree\" xmlns=\"http://www.w3.org/2000/svg\" width=\"${width}\" height=\"${height}\" viewBox=\"0 0 ${width} ${height}\" role=\"img\" aria-label=\"Application resource tree\">"
        all.each { Node p ->
            p.children.each { Node c ->
                int x1 = x(p) + NODE_W, y1 = y(p) + (int) (NODE_H / 2)
                int x2 = x(c), y2 = y(c) + (int) (NODE_H / 2)
                int mx = (int) ((x1 + x2) / 2)
                sb << "<path class=\"argocd-edge\" d=\"M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}\"/>"
            }
        }
        all.each { Node n -> sb << nodeSvg(n) }
        sb << '</svg>'
        sb.toString()
    }

    private static String nodeSvg(Node n) {
        int x = x(n), y = y(n)
        String cls = "argocd-node argocd-hn-${css(n.health)} argocd-sn-${css(n.sync)}${n.uid == 'app' ? ' argocd-node-app' : ''}${n.selected ? ' argocd-node-selected' : ''}${n.failed ? ' argocd-node-failed' : ''}"
        StringBuilder g = new StringBuilder()
        g << "<g class=\"${cls}\" transform=\"translate(${x},${y})\">"
        g << "<title>${esc(n.kind)} ${esc(n.namespace ? n.namespace + '/' : '')}${esc(n.name)}${n.health ? ' — ' + esc(n.health) : ''}${n.sync ? ' — ' + esc(n.sync) : ''}</title>"
        g << "<rect class=\"argocd-node-box\" width=\"${NODE_W}\" height=\"${NODE_H}\" rx=\"6\"/>"
        g << "<rect class=\"argocd-node-health argocd-h-${css(n.health)}\" width=\"5\" height=\"${NODE_H}\" rx=\"2\"/>"
        g << "<rect class=\"argocd-kind-badge\" x=\"11\" y=\"${(int) (NODE_H / 2) - 10}\" width=\"34\" height=\"20\" rx=\"3\"/>"
        g << "<text class=\"argocd-kind-abbr\" x=\"28\" y=\"${(int) (NODE_H / 2) + 4}\" text-anchor=\"middle\">${esc(abbr(n.kind))}</text>"
        g << "<text class=\"argocd-node-name\" x=\"50\" y=\"21\">${esc(trim(n.name, 21))}</text>"
        g << "<text class=\"argocd-node-kind\" x=\"50\" y=\"38\">${esc(trim(n.kind + (n.info ? ' · ' + n.info : ''), 22))}</text>"
        if (n.detail) g << "<text class=\"argocd-node-detail\" x=\"50\" y=\"55\">${esc(trim(n.detail, 24))}</text>"
        if (n.restarts > 0) {
            g << "<text class=\"argocd-restarts\" x=\"${NODE_W - 8}\" y=\"55\" text-anchor=\"end\">${n.restarts} restart${n.restarts == 1 ? '' : 's'}</text>"
        }
        g << statusIcons(n)
        g << '</g>'
        n.href ? "<a href=\"${esc(n.href)}\">${g}</a>" : g.toString()
    }

    /** Health and sync as plain text labels at the right edge (no icons). */
    private static String statusIcons(Node n) {
        StringBuilder s = new StringBuilder()
        int rx = NODE_W - 8
        if (n.health) s << "<text class=\"argocd-status-text argocd-h-${css(n.health)}\" x=\"${rx}\" y=\"21\" text-anchor=\"end\">${esc(n.health)}</text>"
        if (n.sync) s << "<text class=\"argocd-status-text argocd-s-${css(n.sync)}\" x=\"${rx}\" y=\"38\" text-anchor=\"end\">${esc(n.sync)}</text>"
        s.toString()
    }

    static String abbr(String kind) {
        Map m = [Application: 'app', Deployment: 'deploy', ReplicaSet: 'rs', Pod: 'pod', Service: 'svc',
                 Endpoints: 'ep', EndpointSlice: 'eps', ConfigMap: 'cm', Secret: 'sec', Ingress: 'ing',
                 StatefulSet: 'sts', DaemonSet: 'ds', Job: 'job', CronJob: 'cj', PersistentVolumeClaim: 'pvc',
                 ServiceAccount: 'sa', Role: 'role', RoleBinding: 'rb', ClusterRole: 'crole',
                 ClusterRoleBinding: 'crb', Namespace: 'ns', HorizontalPodAutoscaler: 'hpa', NetworkPolicy: 'netpol']
        m[kind] ?: (kind ?: '?').replaceAll(/[a-z]/, '').toLowerCase().take(4) ?: kind.take(3).toLowerCase()
    }

    private static int kindOrder(String kind) {
        ['Namespace', 'ServiceAccount', 'Secret', 'ConfigMap', 'PersistentVolumeClaim', 'Service', 'Deployment',
         'StatefulSet', 'DaemonSet', 'ReplicaSet', 'Pod', 'Ingress', 'Endpoints', 'EndpointSlice'].indexOf(kind).with { it < 0 ? 99 : it }
    }

    private static String infoLine(Map n) {
        List info = (n.info ?: []) as List
        Map status = info.find { it.name == 'Status Reason' } as Map
        Map containers = info.find { it.name == 'Containers' } as Map
        [status?.value, containers?.value].findAll().join(' ')
    }

    /** Extra line revealed when the customer zooms in: images, node, IPs, other info items. */
    private static String detailLine(Map n) {
        List info = ((n.info ?: []) as List).findAll { !(it.name in ['Status Reason', 'Containers', 'Restart Count']) }
        List parts = info.collect { "${it.name}: ${it.value}" }
        if (n.images) parts << ((n.images as List).collect { (it as String).tokenize('/').last() }.join(', '))
        if (n.networkingInfo?.externalURLs) parts << (n.networkingInfo.externalURLs as List).join(' ')
        parts.join(' · ')
    }

    private static int restarts(Map n) {
        Map r = ((n.info ?: []) as List).find { it.name == 'Restart Count' } as Map
        try { r ? (r.value as String).toInteger() : 0 } catch (Exception ignored) { 0 }
    }

    /** Argo CD built-in resource actions offered on a selected node. */
    static boolean restartable(String kind) { kind in ['Deployment', 'StatefulSet', 'DaemonSet'] }

    static String key(String kind, String ns, String name) { "${kind}/${ns ?: ''}/${name}".toString() }

    private static String trim(String s, int max) { s == null ? '' : (s.length() > max ? s.take(max - 1) + '…' : s) }

    private static String css(String s) { (s ?: 'none').toLowerCase().replaceAll(/[^a-z]/, '') }

    static String esc(String s) {
        s == null ? '' : s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
    }
}
