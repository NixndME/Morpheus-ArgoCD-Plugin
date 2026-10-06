import com.morpheuslab.argocd.ArgoApp
import com.morpheuslab.argocd.ArgoCdClient
import com.morpheuslab.argocd.ClusterMatcher
import com.morpheuslab.argocd.K8sProbe
import groovy.json.JsonOutput
import spock.lang.Specification

class ClusterMatcherSpec extends Specification {

    static final String ARGO = 'https://argocd.example.com'
    static final String API = 'https://10.0.0.10:6443'

    static ArgoApp app(String name, Map dest) {
        ArgoApp.parse([metadata: [name: name], spec: [destination: dest + [namespace: 'demo']], status: [:]])
    }

    /** Argo CD fake: every app's tree holds one Service "web" with uid "<app>-uid". */
    ArgoCdClient argo() {
        new ArgoCdClient(baseUrl: ARGO, token: 't', transport: { String m, String url, String b, String tok, boolean v ->
            def app = (url =~ /applications\/([^\/]+)\/resource-tree/)
            app.find() ? [status: 200, body: JsonOutput.toJson([nodes: [[kind: 'Service', name: 'web', namespace: 'demo', uid: "${app.group(1)}-uid"]]])]
                       : [status: 404, body: '{}']
        })
    }

    /** The Morpheus cluster's API: objects it holds (path -> uid), and its CA key ids per URL. */
    K8sProbe cluster(Map<String, String> objects, Map<String, String> caByUrl = [:], String token = 'k') {
        new K8sProbe(apiUrl: API, token: token,
            transport: { String m, String url, String b, String tok, boolean v ->
                String uid = objects[url - API]
                uid ? [status: 200, body: JsonOutput.toJson([metadata: [uid: uid]])] : [status: 404, body: '{}']
            },
            akiReader: { String url -> caByUrl[url] })
    }

    def setup() { ClusterMatcher.clearCache() }

    ClusterMatcher matcher(K8sProbe probe, List<String> hosts = []) {
        new ClusterMatcher(clusterApiUrl: API, argoUrl: ARGO, client: argo(), probe: probe, clusterHosts: hosts)
    }

    def 'resource uid proves the in-cluster destination even though the URLs differ'() {
        given:
        def apps = [app('guestbook', [server: 'https://kubernetes.default.svc'])]
        expect:
        matcher(cluster(['/api/v1/namespaces/demo/services/web': 'guestbook-uid'])).match([], apps)*.method == ['uid']
    }

    def 'same object name with a different uid is another cluster, even with the same URL'() {
        given:
        def apps = [app('guestbook', [server: API])]
        expect:
        matcher(cluster(['/api/v1/namespaces/demo/services/web': 'someone-else'])).match([], apps).isEmpty()
    }

    def 'a destination without apps never matches'() {
        expect:
        matcher(cluster([:])).match([[name: 'hks', server: API]], []).isEmpty()
    }

    def 'cluster names are not trusted: same name, unproven server, no match'() {
        given:
        def argoClusters = [[name: 'HKS', server: 'https://other.example.com:6443']]
        def apps = [app('a', [name: 'HKS'])]
        expect:
        matcher(cluster([:], [(API): 'AA', 'https://other.example.com:6443': 'BB'])).match(argoClusters, apps).isEmpty()
    }

    def 'cluster CA proves a match when uids cannot be checked (no token)'() {
        given:
        def apps = [app('a', [server: 'https://k8s.example.com:443'])]
        def probe = cluster([:], [(API): 'AA:BB', 'https://k8s.example.com:443': 'AA:BB'], null)
        expect:
        matcher(probe).match([[name: 'prod', server: 'https://k8s.example.com:443']], apps)*.name == ['prod']
        matcher(probe).match([], apps)*.method == ['ca']
    }

    def 'falls back to the API URL, and to in-cluster when Argo CD runs on our nodes'() {
        given:
        def probe = cluster([:], [:], null)
        expect:
        matcher(probe).match([], [app('a', [server: API + '/'])])*.method == ['url']
        matcher(probe, ['argocd.example.com']).match([], [app('b', [server: 'https://kubernetes.default.svc'])])*.method == ['host']
        matcher(probe).match([], [app('c', [server: 'https://kubernetes.default.svc'])]).isEmpty()
    }

    def 'targets() follows proved matches by server or Argo CD cluster name'() {
        given:
        def m = [new ClusterMatcher.Match(server: 'https://k8s.example.com', name: 'prod', method: 'uid', apps: 1)]
        expect:
        ClusterMatcher.targets(app('a', [name: 'prod']), m)
        ClusterMatcher.targets(app('b', [server: 'https://K8S.example.com/']), m)
        !ClusterMatcher.targets(app('c', [server: 'https://x.example.com']), m)
    }

    def 'reads the authority key id from a certificate extension'() {
        given: 'DER: OCTET STRING { SEQUENCE { [0] 15:7B:D2 } }'
        byte[] ext = [0x04, 0x07, 0x30, 0x05, 0x80, 0x03, 0x15, 0x7B, 0xD2] as byte[]
        expect:
        K8sProbe.akiHex(ext) == '15:7B:D2'
        K8sProbe.path('Deployment', 'demo', 'web') == '/apis/apps/v1/namespaces/demo/deployments/web'
        K8sProbe.path('Namespace', null, 'demo') == '/api/v1/namespaces/demo'
        K8sProbe.path('Widget', 'demo', 'x') == null
    }

    def '100 clusters with 100 workloads each: only the one cluster running the Argo CD app gets the tab'() {
        given: 'Argo CD manages one app, deployed to cluster 57, reached through an address Morpheus does not use'
        int treeCalls = 0
        def argoClient = new ArgoCdClient(baseUrl: ARGO, token: 't', transport: { String m, String url, String b, String tok, boolean v ->
            treeCalls++
            [status: 200, body: JsonOutput.toJson([nodes: [[kind: 'Deployment', name: 'shop', namespace: 'shop', uid: 'uid-on-57']]])]
        })
        def apps = [app('shop', [server: 'https://lb.example.com:6443'])]
        def argoClusters = [[name: 'prod-eu', server: 'https://lb.example.com:6443']]

        and: 'every Morpheus cluster has its own 100 workloads, all named alike'
        int k8sCalls = 0
        def clusterProbe = { int n ->
            Map objects = (1..100).collectEntries { ["/apis/apps/v1/namespaces/app${it}/deployments/web".toString(), "c${n}-w${it}".toString()] }
            if (n == 57) objects['/apis/apps/v1/namespaces/shop/deployments/shop'] = 'uid-on-57'
            new K8sProbe(apiUrl: "https://10.1.0.${n}:6443", token: 'k',
                transport: { String m, String url, String b, String tok, boolean v ->
                    k8sCalls++
                    String uid = objects[url - "https://10.1.0.${n}:6443"]
                    uid ? [status: 200, body: JsonOutput.toJson([metadata: [uid: uid]])] : [status: 404, body: '{}']
                },
                akiReader: { String url -> null })
        }

        when:
        List<Integer> withTab = (1..100).findAll { int n ->
            new ClusterMatcher(clusterApiUrl: "https://10.1.0.${n}:6443", argoUrl: ARGO, client: argoClient, probe: clusterProbe(n))
                .match(argoClusters, apps)
        }

        then:
        withTab == [57]
        treeCalls == 1          // Argo CD is asked once, not once per cluster
        k8sCalls == 100         // one lookup per cluster
    }

    def 'an unreachable cluster API is tried once, not once per destination'() {
        given:
        int calls = 0
        def probe = new K8sProbe(apiUrl: API, token: 'k', akiReader: { String u -> null },
            transport: { String m, String url, String b, String tok, boolean v -> calls++; throw new ConnectException('timed out') })
        def apps = (1..20).collect { app("a${it}", [server: "https://k${it}.example.com"]) }
        expect:
        matcher(probe).match([], apps).isEmpty()
        calls == 1
    }
}
