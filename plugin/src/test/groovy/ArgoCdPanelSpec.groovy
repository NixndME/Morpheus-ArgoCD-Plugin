import com.morpheuslab.argocd.ArgoApp
import com.morpheuslab.argocd.AppView
import com.morpheuslab.argocd.ArgoCdClient
import com.morpheuslab.argocd.ArgoCdSettings
import com.morpheuslab.argocd.ArgoResult
import com.morpheuslab.argocd.ClusterPanel
import com.morpheuslab.argocd.PanelState
import com.morpheuslab.argocd.ResourceTree
import groovy.json.JsonOutput
import spock.lang.Specification

class ArgoCdPanelSpec extends Specification {

    static Map app(String name, String server, String sync = 'Synced', String health = 'Healthy') {
        [metadata: [name: name],
         spec    : [project: 'default', source: [repoURL: 'https://github.com/argoproj/argocd-example-apps.git', path: name, targetRevision: 'HEAD'],
                    destination: [server: server, namespace: name]],
         status  : [sync: [status: sync, revision: 'abcdef1234567'], health: [status: health],
                    operationState: [phase: 'Succeeded', finishedAt: '2026-10-06T08:00:00Z'],
                    resources: [[kind: 'Service', name: "${name}-ui", namespace: name, status: 'Synced', health: [status: 'Healthy']],
                                [group: 'apps', kind: 'Deployment', name: "${name}-ui", namespace: name, status: sync, health: [status: health]]]]]
    }

    static Map tree(String name) {
        [nodes: [
            [kind: 'Service', name: "${name}-ui", namespace: name, uid: 's1', health: [status: 'Healthy']],
            [group: 'apps', kind: 'Deployment', name: "${name}-ui", namespace: name, uid: 'd1', health: [status: 'Healthy']],
            [group: 'apps', kind: 'ReplicaSet', name: "${name}-ui-7d9", namespace: name, uid: 'r1', parentRefs: [[kind: 'Deployment', uid: 'd1']], health: [status: 'Healthy']],
            [kind: 'Pod', name: "${name}-ui-7d9-abc", namespace: name, uid: 'p1', parentRefs: [[kind: 'ReplicaSet', uid: 'r1']], health: [status: 'Degraded'],
             info: [[name: 'Status Reason', value: 'CrashLoopBackOff'], [name: 'Containers', value: '0/1'], [name: 'Restart Count', value: '4'], [name: 'Node', value: 'hks-worker-1']],
             images: ['gcr.io/heptio-images/ks-guestbook-demo:0.2']],
            [kind: 'EndpointSlice', name: "${name}-ui-x", namespace: name, uid: 'e1', parentRefs: [[kind: 'Service', uid: 's1']]]
        ]]
    }

    ArgoCdClient fakeClient(Map<String, Object> routes) {
        new ArgoCdClient(baseUrl: 'https://10.0.0.10:30443', token: 't', transport: { String m, String url, String body, String tok, boolean v ->
            String path = url - 'https://10.0.0.10:30443'
            def hit = routes.find { k, val -> "${m} ${path}".startsWith(k) }
            if (!hit) return [status: 404, body: '{"message":"no route ' + path + '"}']
            def val = hit.value
            val instanceof Integer ? [status: val, body: '{"message":"nope"}'] : [status: 200, body: JsonOutput.toJson(val)]
        })
    }

    def 'parses an Argo CD application into display values'() {
        when:
        ArgoApp a = ArgoApp.parse(app('guestbook', 'https://kubernetes.default.svc', 'OutOfSync', 'Progressing'))
        then:
        a.name == 'guestbook'
        a.syncClass.contains('warn')
        a.healthClass.contains('info')
        a.shortRevision == 'abcdef12'
        a.source.endsWith('/ guestbook')
        a.resources.size() == 2
        a.confirmPattern == 'guestbook'
        ArgoApp.parse([metadata: [name: 'a.b']]).confirmPattern == 'a\\.b'
    }

    def 'panel lists only apps that target this cluster (API url or in-cluster when Argo CD runs here)'() {
        given:
        def client = fakeClient([
            'GET /api/version'            : [Version: 'v3.5.3'],
            'GET /api/v1/session/userinfo': [username: 'morpheus'],
            'GET /api/v1/applications'    : [items: [app('guestbook', 'https://kubernetes.default.svc'),
                                                     app('remote', 'https://10.9.9.9:6443'),
                                                     app('direct', 'https://10.0.0.10:6443')]],
            'GET /api/v1/clusters'        : [items: [[name: 'in-cluster', server: 'https://kubernetes.default.svc']]],
            'GET /api/v1/projects'        : [items: [[metadata: [name: 'default']], [metadata: [name: 'team-a']]]]
        ])
        when:
        ClusterPanel p = ClusterPanel.build(clusterId: 1L, clusterName: 'HKS', clusterApiUrl: 'https://10.0.0.10:6443',
            clusterHosts: ['10.0.0.10', '10.0.0.11'], accessLevel: 'full',
            settings: new ArgoCdSettings(url: 'https://10.0.0.10:30443', token: 't'), client: client)
        then:
        p.isOk
        p.argoRunsHere
        p.apps*.name == ['direct', 'guestbook']
        p.otherClusterApps == 1
        p.newAppServer == ClusterPanel.IN_CLUSTER
        p.canCreate
        p.projects == ['default', 'team-a']
        p.argocdVersion == 'v3.5.3'
    }

    def 'an unknown token becomes an explained error state, not an empty panel'() {
        given:
        def client = fakeClient(['GET /api/version': [Version: 'v3'], 'GET /api/v1/applications': 401])
        when:
        ClusterPanel p = ClusterPanel.build(clusterId: 1L, clusterApiUrl: 'https://x:6443', accessLevel: 'read',
            settings: new ArgoCdSettings(url: 'https://10.0.0.10:30443', token: 't'), client: client)
        then:
        p.hasError
        p.stateMessage.contains('rejected the API token')
        !p.canManage
    }

    def 'not configured is its own state'() {
        expect:
        ClusterPanel.build(clusterId: 1L, accessLevel: 'full', settings: new ArgoCdSettings()).notConfigured
    }

    def 'resource tree nests children under owners, marks failures and renders clickable SVG with zoom css'() {
        given:
        ArgoApp a = ArgoApp.parse(app('guestbook', 'https://kubernetes.default.svc'))
        when:
        ResourceTree t = ResourceTree.build(a, tree('guestbook'), { n -> "/plugin/argocd/select?name=${n.name}" }, 'p1')
        String svg = t.toSvg()
        then:
        t.root.children*.kind == ['Service', 'Deployment']
        t.root.children[1].children[0].kind == 'ReplicaSet'
        def pod = t.root.children[1].children[0].children[0]
        pod.kind == 'Pod' && pod.restarts == 4 && pod.failed && pod.selected
        pod.info == 'CrashLoopBackOff 0/1'
        pod.detail.contains('hks-worker-1') && pod.detail.contains('ks-guestbook-demo:0.2')
        svg.startsWith('<svg')
        svg.contains('href="/plugin/argocd/select?name=guestbook-ui-7d9-abc"')
        svg.contains('argocd-node-failed')
        svg.contains('4 restarts') && !svg.contains('&#')
        !svg.contains('<script')
        t.zoomCss().contains('#argocd-zoom-200:checked')
        t.zoomOptions.size() == 7
    }

    def 'select flow keeps per-user per-cluster state and the flash shows once'() {
        when:
        PanelState.update(7L, 1L) { s -> s.selectedApp = 'guestbook'; s.flashLevel = 'success'; s.flashMessage = 'ok' }
        then:
        PanelState.get(7L, 1L).selectedApp == 'guestbook'
        PanelState.get(8L, 1L) == null
        PanelState.takeFlash(7L, 1L).message == 'ok'
        PanelState.takeFlash(7L, 1L) == null
    }

    def 'pod logs are parsed from Argo CD newline-delimited JSON'() {
        given:
        def client = new ArgoCdClient(baseUrl: 'https://a', token: 't', transport: { m, u, b, t, v ->
            [status: 200, body: '{"result":{"content":"line one","podName":"p"}}\n{"result":{"content":"line two"}}\n{"result":{"content":"","last":true}}']
        })
        when:
        ArgoResult r = client.podLogs('guestbook', [kind: 'Pod', name: 'p', namespace: 'guestbook'], 50)
        then:
        r.ok
        r.data == ['line one', 'line two']
    }

    def 'manifest view hides managedFields and last-applied annotation'() {
        when:
        String out = ClusterPanel.pretty('{"kind":"Deployment","metadata":{"name":"x","managedFields":[{"a":1}],"annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{...}","keep":"me"}}}')
        then:
        !out.contains('managedFields')
        !out.contains('last-applied')
        out.contains('"keep"')
    }

    def 'app view is pre-rendered for instant CSS open/close and node selection'() {
        given:
        def client = fakeClient([
            'GET /api/v1/applications/guestbook/resource-tree': tree('guestbook'),
            'GET /api/v1/applications/guestbook/events?resourceName=guestbook-ui-7d9-abc': [items: [[type: 'Normal', reason: 'Pulled', message: 'ok', involvedObject: [kind: 'Pod', name: 'guestbook-ui-7d9-abc']]]],
            'GET /api/v1/applications/guestbook/events'       : [items: []],
            'GET /api/v1/applications/guestbook/resource?'    : [manifest: '{"kind":"Pod","metadata":{"name":"p","managedFields":[]}}'],
            'GET /api/v1/applications/guestbook/logs'         : [result: [content: 'hello']]
        ])
        when:
        AppView v = AppView.build(0, ArgoApp.parse(app('guestbook', 'https://kubernetes.default.svc')), client, 'p1', true)
        def pod = v.nodes.find { it.kind == 'Pod' }
        then:
        v.hasTree
        v.treeHtml.contains('<label for="argocd-a0-n')
        !v.treeHtml.contains('<script')
        v.selectedNode == pod.index
        pod.events*.reason == ['Pulled']
        pod.logs == 'hello'
        !pod.manifest.contains('managedFields')
        v.css.contains("#argocd-a0-n${pod.index}:checked~.argocd-nodecards .argocd-card-${pod.index}{display:block}")
        v.css.contains('#argocd-a0-z-150:checked~.argocd-graph .argocd-stage{zoom:1.5}')
        v.css.contains('label[for=argocd-a0-z-fit]')
        v.nodeRadios.find { it.checked }.id == "argocd-a0-n${pod.index}"
    }
}
