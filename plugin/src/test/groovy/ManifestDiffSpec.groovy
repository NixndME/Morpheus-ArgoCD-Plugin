import com.morpheuslab.argocd.ManifestDiff
import groovy.json.JsonOutput
import spock.lang.Specification

class ManifestDiffSpec extends Specification {

    static String deploy(int replicas, Map extraMeta = [:]) {
        JsonOutput.toJson([apiVersion: 'apps/v1', kind: 'Deployment',
            metadata: [name: 'web', namespace: 'demo'] + extraMeta,
            spec: [replicas: replicas, template: [spec: [containers: [[name: 'web', image: 'nginx:1.27']]]]],
            status: [readyReplicas: replicas]])
    }

    def 'shows what runs now (-) before what Git wants (+)'() {
        when:
        def rows = ManifestDiff.of(deploy(1), deploy(2))
        then:
        rows.findAll { it.cls != 'ctx' && it.cls != 'gap' }*.text == ['-   replicas: 2', '+   replicas: 1']
    }

    def 'same manifests, or differences only in fields Kubernetes fills in, give no diff'() {
        expect:
        ManifestDiff.of(deploy(1), deploy(1)).isEmpty()
        ManifestDiff.of(deploy(1), deploy(1, [uid: 'abc', resourceVersion: '42', managedFields: [[manager: 'kubectl']]])).isEmpty()
    }

    def 'writes readable YAML, lists included'() {
        expect:
        ManifestDiff.yaml(ManifestDiff.clean(ManifestDiff.parse(deploy(3)))) == [
            'apiVersion: apps/v1', 'kind: Deployment', 'metadata:', '  name: web', '  namespace: demo',
            'spec:', '  replicas: 3', '  template:', '    spec:', '      containers:', '      - name: web', '        image: nginx:1.27']
    }

    def 'a missing resource is all additions'() {
        expect:
        ManifestDiff.of(deploy(1), null).every { it.cls in ['add', 'gap'] }
    }
}
