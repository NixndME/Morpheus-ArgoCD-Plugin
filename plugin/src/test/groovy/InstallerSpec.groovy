import com.morpheuslab.argocd.Installer
import com.morpheuslab.argocd.Kube
import groovy.json.JsonOutput
import spock.lang.Specification

class InstallerSpec extends Specification {

    static final String URL = 'https://10.0.0.5:30443'

    /** A Kube whose API answers from a map of "METHOD path" to [status, body]. Unknown calls get 404. */
    static Kube fake(Map<String, List> answers, List<String> calls = []) {
        Kube k = new Kube(apiUrl: 'https://10.0.0.5:6443', token: 't')
        k.transport = { String method, String url, String body, String ctype, String bearer ->
            String path = url - 'https://10.0.0.5:6443'
            calls << "${method} ${path}".toString()
            List a = answers["${method} ${path}".toString()] ?: answers["${method} ${path.split('\\?')[0]}".toString()]
            a ? [status: a[0], body: a[1] instanceof String ? a[1] : JsonOutput.toJson(a[1])] : [status: 404, body: '{"message":"not found"}']
        }
        k
    }

    def 'bundled version comes from the argocd image'() {
        expect:
        Installer.bundledVersion() == 'v3.6.1'
        Installer.manifest().size() == 60
    }

    def 'prepare puts namespaced objects in argocd and leaves cluster objects alone'() {
        when:
        List<Map> out = Installer.prepare(Installer.manifest(), URL)

        then:
        out.findAll { it.kind in ['CustomResourceDefinition', 'ClusterRole', 'ClusterRoleBinding'] }.every { !it.metadata.namespace }
        out.findAll { !(it.kind in ['CustomResourceDefinition', 'ClusterRole', 'ClusterRoleBinding']) }.every { it.metadata.namespace == 'argocd' }
    }

    def 'prepare adds the morpheus account, its role, the address and a NodePort'() {
        when:
        List<Map> out = Installer.prepare(Installer.manifest(), URL)
        Map cm = out.find { it.kind == 'ConfigMap' && it.metadata.name == 'argocd-cm' }
        Map rbac = out.find { it.kind == 'ConfigMap' && it.metadata.name == 'argocd-rbac-cm' }
        Map svc = out.find { it.kind == 'Service' && it.metadata.name == Installer.SERVICE }

        then:
        cm.data['accounts.morpheus'] == 'apiKey'
        cm.data.url == URL
        rbac.data['policy.csv'].contains('p, role:morpheus, applications, *, */*, allow')
        rbac.data['policy.csv'].contains('g, morpheus, role:morpheus')
        svc.spec.type == 'NodePort'
        svc.spec.ports[0].nodePort == 30443
        svc.spec.selector == ['app.kubernetes.io/name': 'argocd-server']
    }

    def 'prepare does not change the bundled objects'() {
        given:
        List<Map> before = Installer.manifest()

        when:
        Installer.prepare(before, URL)

        then:
        !before.find { it.metadata.name == 'argocd-cm' }.data?.containsKey('accounts.morpheus')
        before.every { !it.metadata.namespace }
    }

    def 'status is none when no Argo CD server runs'() {
        expect:
        Installer.status(fake(['GET /apis/apps/v1/deployments': [200, [items: []]]])).state == 'none'
    }

    def 'status is ours when our marker is next to the server'() {
        given:
        Map dep = [metadata: [name: 'argocd-server', namespace: 'argocd'], status: [readyReplicas: 1],
                   spec: [template: [spec: [containers: [[name: 'argocd-server', image: 'quay.io/argoproj/argocd:v3.6.1']]]]]]
        Kube k = fake(['GET /apis/apps/v1/deployments': [200, [items: [dep]]],
                       'GET /api/v1/namespaces/argocd/configmaps/morpheus-argocd': [200, [data: [url: URL]]]])

        when:
        Map st = Installer.status(k)

        then:
        st.state == 'ours'
        st.version == 'v3.6.1'
        st.ready
        st.url == URL
    }

    def 'status is other for an Argo CD this plugin did not install'() {
        given:
        Map dep = [metadata: [name: 'argocd-server', namespace: 'gitops'], status: [:],
                   spec: [template: [spec: [containers: [[name: 'argocd-server', image: 'quay.io/argoproj/argocd:v3.5.6']]]]]]

        expect:
        Installer.status(fake(['GET /apis/apps/v1/deployments': [200, [items: [dep]]]])) .with {
            state == 'other' && namespace == 'gitops' && version == 'v3.5.6' && !ready
        }
    }

    def 'status reports an unreachable cluster'() {
        expect:
        Installer.status(fake(['GET /apis/apps/v1/deployments': [0, '']])).state == 'error'
    }

    def 'every object we create carries our label, so Remove deletes only ours'() {
        expect:
        Installer.prepare(Installer.manifest(), URL).every { it.metadata.labels['app.kubernetes.io/managed-by'] == Installer.MARKER }
    }

    def 'install refuses Argo CD parts this plugin did not make'() {
        given:
        Map crd = [metadata: [name: 'applications.argoproj.io', labels: [:]]]

        expect:
        Installer.conflict(fake([:])) == null
        Installer.conflict(fake(['GET /api/v1/namespaces/argocd': [200, [metadata: [name: 'argocd']]]])).contains('namespace argocd')
        Installer.conflict(fake(['GET /api/v1/namespaces/argocd': [200, [metadata: [name: 'argocd']]],
                                 'GET /api/v1/namespaces/argocd/configmaps/morpheus-argocd': [200, [data: [:]]]])) == null
        Installer.conflict(fake(['GET /apis/apiextensions.k8s.io/v1/customresourcedefinitions/applications.argoproj.io': [200, crd]])).contains('resource types')
    }

    def 'remove only works on our Argo CD in namespace argocd'() {
        given:
        Map dep = [metadata: [name: 'argocd-server', namespace: 'gitops'], status: [:], spec: [template: [spec: [containers: []]]]]
        Kube k = fake(['GET /apis/apps/v1/deployments': [200, [items: [dep]]],
                       'GET /api/v1/namespaces/gitops/configmaps/morpheus-argocd': [200, [data: [:]]]])

        expect:
        Installer.remove(k, {}).contains('namespace argocd')
    }

    def 'addresses that point back at the appliance are refused'() {
        expect:
        Installer.unsafeUrl(url) as boolean == refused

        where:
        url                                   || refused
        'https://172.16.5.10:30443'          || false
        'https://10.0.0.5'                    || false
        'https://127.0.0.1:443'               || true
        'https://localhost'                   || true
        'https://169.254.169.254/latest'      || true
        'https://0.0.0.0'                     || true
        'https://no-such-host.invalid'        || true
    }

    def 'node addresses: workers first, external before internal, no host names'() {
        given:
        Map master = [metadata: [name: 'm', labels: ['node-role.kubernetes.io/control-plane': '']],
                      status: [addresses: [[type: 'InternalIP', address: '10.0.0.5'], [type: 'Hostname', address: 'm']]]]
        Map worker = [metadata: [name: 'w', labels: [:]],
                      status: [addresses: [[type: 'InternalIP', address: '10.0.0.6'], [type: 'ExternalIP', address: '203.0.113.6']]]]

        expect:
        Installer.nodeAddresses(fake(['GET /api/v1/nodes': [200, [items: [master, worker]]]])) == ['203.0.113.6', '10.0.0.6', '10.0.0.5']
        Installer.nodeAddresses(fake([:])) == []
    }

    def 'candidates keep Morpheus worker addresses first, then Kubernetes, then the API host, once each'() {
        expect:
        Installer.candidates(['203.0.113.6', '10.0.0.6'], ['10.0.0.6', '10.0.0.5'], 'api.example.com') ==
            ['203.0.113.6', '10.0.0.6', '10.0.0.5', 'api.example.com']
        Installer.candidates([null, ''], [], '10.0.0.5') == ['10.0.0.5']
    }

    def 'the first address that answers on the Argo CD port wins'() {
        given:
        List<String> tried = []
        Closure answers = { String url -> tried << url; url.contains('10.0.0.6') }

        expect:
        Installer.pickUrl(['203.0.113.6', '10.0.0.6', '10.0.0.5'], 30443, answers) == 'https://10.0.0.6:30443'
        tried == ['https://203.0.113.6:30443', 'https://10.0.0.6:30443']
        Installer.pickUrl(['203.0.113.6'], 30443, { false }) == null
    }

    def 'the NodePort made first is not created a second time (Kubernetes refuses it as port in use)'() {
        when:
        List<Map> rest = Installer.afterTypes(Installer.prepare(Installer.manifest(), URL))

        then:
        !rest.any { it.kind == 'Service' && it.metadata.name == Installer.SERVICE }
        !rest.any { it.kind == 'CustomResourceDefinition' }
        rest.size() == 57
    }

    def 'token step fails clearly without the first admin password'() {
        expect:
        Installer.makeToken(fake([:]), URL).error.contains('admin password')
    }
}
