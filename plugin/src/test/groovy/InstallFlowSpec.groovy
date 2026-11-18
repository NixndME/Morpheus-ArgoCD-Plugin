import com.morpheuslab.argocd.Installer
import com.morpheuslab.argocd.Kube
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification

/** Install, connect and remove against a small in-memory Kubernetes API. */
class InstallFlowSpec extends Specification {

    static final String API = 'https://10.0.0.5:6443'
    static final String CERT = InstallFlowSpec.getResourceAsStream('/test-cert.b64').text.trim()

    /** Objects by path. POST creates (409 when the name exists), PUT replaces, GET reads, DELETE removes. */
    static class FakeApi {
        Map<String, Map> objects = [:]
        List<String> calls = []
        Map<String, Closure<List>> special = [:]

        Map handle(String method, String url, String body) {
            String path = url - API
            String bare = path.split('\\?')[0]
            calls << "${method} ${path}".toString()
            Closure<List> s = special["${method} ${path}".toString()] ?: special["${method} ${bare}".toString()]
            if (s) { List r = s.call(body); return [status: r[0], body: r[1] instanceof String ? r[1] : JsonOutput.toJson(r[1])] }
            Map obj = body ? (Map) new JsonSlurper().parseText(body) : null
            // like Kubernetes: a Secret's stringData is stored as base64 data
            if (obj?.kind == 'Secret' && obj.stringData) {
                obj.data = ((obj.data ?: [:]) as Map) + obj.stringData.collectEntries { k, v -> [(k): (v as String).bytes.encodeBase64().toString()] }
                obj.remove('stringData')
            }
            switch (method) {
                case 'POST':
                    String p = "${bare}/${obj.metadata.name}"
                    // like Kubernetes: a taken NodePort fails before the name is checked
                    def port = obj.spec?.ports?.getAt(0)?.nodePort
                    if (obj.kind == 'Service' && port && objects.values().any { it.kind == 'Service' && it.spec?.ports?.getAt(0)?.nodePort == port }) {
                        return [status: 422, body: '{"message":"provided port is already allocated"}']
                    }
                    if (objects[p]) return [status: 409, body: '{"message":"already exists"}']
                    if (obj.kind == 'CustomResourceDefinition') obj.status = [conditions: [[type: 'Established', status: 'True']]]
                    if (obj.kind == 'Deployment') obj.status = [readyReplicas: 1]
                    objects[p] = obj
                    return [status: 201, body: JsonOutput.toJson(obj)]
                case 'PUT':
                    objects[bare] = obj
                    return [status: 200, body: body]
                case 'GET':
                    return objects[bare] ? [status: 200, body: JsonOutput.toJson(objects[bare])] : [status: 404, body: '{"message":"not found"}']
                default:
                    objects.remove(bare)
                    return [status: 200, body: '{}']
            }
        }
    }

    FakeApi api
    Kube kube
    List<String> said = []
    Closure say = { String line -> said << line }

    def setup() {
        api = new FakeApi()
        kube = new Kube(apiUrl: API, token: 't')
        kube.transport = { String m, String url, String body, String ctype, String bearer -> api.handle(m, url, body) }
        // plural names, as the API discovery would answer
        (Installer.manifest() + [[apiVersion: 'v1', kind: 'Namespace'], [apiVersion: 'v1', kind: 'Service'], [apiVersion: 'v1', kind: 'Secret'],
                                 [apiVersion: 'v1', kind: 'ConfigMap']]).each { Map o ->
            String k = (o.kind as String).toLowerCase()
            kube.plurals["${o.apiVersion}/${o.kind}".toString()] = k.endsWith('y') ? k[0..-2] + 'ies' : k + 's'
        }
        api.special['GET /api/v1/nodes'] = { [200, [items: [
            [metadata: [name: 'm', labels: ['node-role.kubernetes.io/control-plane': '']], status: [addresses: [[type: 'InternalIP', address: '10.0.0.5']]]],
            [metadata: [name: 'w', labels: [:]], status: [addresses: [[type: 'InternalIP', address: '10.0.0.6']]]]]]] }
        api.special['GET /api/v1/namespaces/argocd/secrets/argocd-secret'] = { [200, [data: ['tls.crt': CERT]]] }
        api.special['GET /api/v1/namespaces/argocd/secrets/argocd-initial-admin-secret'] = { [200, [data: [password: 'cHc='.toString()]]] }
        Installer.pause = { long ms -> }
        Installer.argoCall = { String m, String url, Map b, String bearer, cert ->
            assert cert != null
            url.endsWith('/api/v1/session') ? [status: 200, data: [token: 'session']] : [status: 200, data: [token: 'plugin-token']]
        }
    }

    def cleanup() {
        Installer.pause = { long ms -> Thread.sleep(ms) }
        Installer.reach = { String url -> Installer.answers(url) }
        Installer.argoCall = { String m, String u, Map b, String bearer, cert -> Installer.argo(m, u, b, bearer, cert) }
    }

    def 'install applies everything, finds the worker address and saves the token'() {
        given:
        Installer.reach = { String url -> url.contains('10.0.0.6') }

        when:
        Map r = Installer.install(kube, ['10.0.0.6'], '10.0.0.5', say)

        then:
        r == [url: 'https://10.0.0.6:30443', token: 'plugin-token']
        api.objects['/api/v1/namespaces/argocd/configmaps/morpheus-argocd'].data.url == 'https://10.0.0.6:30443'
        api.objects['/api/v1/namespaces/argocd/configmaps/argocd-cm'].data['accounts.morpheus'] == 'apiKey'
        Installer.savedToken(kube) == [url: 'https://10.0.0.6:30443', token: 'plugin-token']
        api.objects.keySet().findAll { it.contains('/customresourcedefinitions/') }.size() == 3
        said.any { it.startsWith('Argo CD answers at https://10.0.0.6:30443') }
    }

    def 'the marker is written before anything else, so a stopped install can be removed'() {
        given:
        Installer.reach = { false }

        when:
        Installer.install(kube, ['10.0.0.6'], '10.0.0.5', say)
        int marker = api.calls.findIndexOf { it == 'POST /api/v1/namespaces/argocd/configmaps' }
        int firstCrd = api.calls.findIndexOf { it.contains('customresourcedefinitions') && it.startsWith('POST') }

        then:
        marker >= 0 && marker < firstCrd
    }

    def 'install stops clearly when no node address answers'() {
        given:
        Installer.reach = { false }

        when:
        Map r = Installer.install(kube, ['203.0.113.6'], '10.0.0.5', say)

        then:
        r.error.contains('can not reach port 30443')
        r.error.contains('203.0.113.6')
        !api.objects.containsKey('/api/v1/namespaces/argocd/secrets/morpheus-argocd-token')
    }

    def 'install leaves an Argo CD someone else made alone'() {
        given:
        api.objects['/api/v1/namespaces/argocd'] = [metadata: [name: 'argocd']]

        when:
        Map r = Installer.install(kube, ['10.0.0.6'], '10.0.0.5', say)

        then:
        r.error.contains('namespace argocd')
        !api.calls.any { it.startsWith('POST') || it.startsWith('PUT') }
    }

    def 'Connect makes the token when the install stopped before it'() {
        given:
        Installer.reach = { true }
        Installer.argoCall = { String m, String url, Map b, String bearer, cert -> [status: 0, error: 'down'] }
        Installer.install(kube, ['10.0.0.6'], '10.0.0.5', say)
        Installer.argoCall = { String m, String url, Map b, String bearer, cert ->
            url.endsWith('/api/v1/session') ? [status: 200, data: [token: 's']] : [status: 200, data: [token: 'late-token']]
        }
        api.special['GET /apis/apps/v1/deployments'] = { [200, [items: [api.objects['/apis/apps/v1/namespaces/argocd/deployments/argocd-server']]]] }

        expect:
        Installer.connectToken(kube) == [url: 'https://10.0.0.6:30443', token: 'late-token']
        Installer.savedToken(kube) == [url: 'https://10.0.0.6:30443', token: 'late-token']
    }

    def 'remove lets go of the apps first, then deletes only our labelled objects'() {
        given:
        Installer.reach = { true }
        Installer.install(kube, ['10.0.0.6'], '10.0.0.5', say)
        api.special['GET /apis/apps/v1/deployments'] = { [200, [items: [api.objects['/apis/apps/v1/namespaces/argocd/deployments/argocd-server']]]] }
        api.special['GET /apis/argoproj.io/v1alpha1/namespaces/argocd/applicationsets'] = { [200, [items: [[metadata: [name: 'set', namespace: 'argocd']]]]] }
        api.special['GET /apis/argoproj.io/v1alpha1/namespaces/argocd/applications'] = { [200, [items: [[metadata: [name: 'web', namespace: 'argocd', finalizers: ['resources-finalizer.argocd.argoproj.io']]]]]] }
        api.special['GET /apis/argoproj.io/v1alpha1/namespaces/argocd/appprojects'] = { [200, [items: []]] }
        api.calls.clear()

        when:
        String err = Installer.remove(kube, say)
        List<String> c = api.calls

        then:
        err == null
        c.indexOf('DELETE /apis/argoproj.io/v1alpha1/namespaces/argocd/applicationsets/set') < c.indexOf('DELETE /apis/argoproj.io/v1alpha1/namespaces/argocd/applications/web')
        c.indexOf('PUT /apis/argoproj.io/v1alpha1/namespaces/argocd/applications/web') < c.indexOf('DELETE /apis/argoproj.io/v1alpha1/namespaces/argocd/applications/web')
        c.contains('DELETE /api/v1/namespaces/argocd')
        c.findAll { it.startsWith('DELETE /apis/apiextensions') || it.startsWith('DELETE /apis/rbac') }
            .every { it.contains('labelSelector=app.kubernetes.io%2Fmanaged-by%3Dmorpheus-argocd') }
    }

    def 'remove stops when it can not list the Argo CD apps'() {
        given:
        Installer.reach = { true }
        Installer.install(kube, ['10.0.0.6'], '10.0.0.5', say)
        api.special['GET /apis/apps/v1/deployments'] = { [200, [items: [api.objects['/apis/apps/v1/namespaces/argocd/deployments/argocd-server']]]] }
        api.special['GET /apis/argoproj.io/v1alpha1/namespaces/argocd/applicationsets'] = { [500, [message: 'boom']] }

        expect:
        Installer.remove(kube, say).contains('Could not list')
        !api.calls.contains('DELETE /api/v1/namespaces/argocd')
    }
}
