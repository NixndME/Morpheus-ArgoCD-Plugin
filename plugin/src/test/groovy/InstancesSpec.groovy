import com.morpheuslab.argocd.ArgoInstance
import com.morpheuslab.argocd.ArgoCdSettings
import com.morpheuslab.argocd.Instances
import spock.lang.Specification

class InstancesSpec extends Specification {

    def 'a connection is stored as JSON and read back'() {
        when:
        String json = Instances.toJson(url: 'https://10.0.0.6:30443', token: 't', verifyTls: false, project: 'default')
        Map back = Instances.parse(json)

        then:
        back == [url: 'https://10.0.0.6:30443', token: 't', verifyTls: false, project: 'default']
        Instances.parse(null) == null
        Instances.parse('') == null
        Instances.parse('not json') == null
        Instances.parse('{"url":"https://x"}') == null
    }

    def 'an integration with a connection becomes an Argo CD the plugin can use'() {
        when:
        ArgoInstance a = Instances.instance(12L, 'Lab', 3L, false, [url: 'https://10.0.0.6:30443/', token: 't', verifyTls: false, project: 'p'])

        then:
        a.id == 12L
        a.label == 'Lab'
        a.settings().url == 'https://10.0.0.6:30443'
        a.settings().token == 't'
        !a.settings().verifyTls
        a.settings().defaultProject == 'p'
    }

    def 'Argo CDs of the main tenant are for everyone, a tenant one only for its tenant'() {
        given:
        ArgoInstance global = Instances.instance(11L, 'Prod', 1L, true, [url: 'https://a', token: 't'])
        ArgoInstance acme = Instances.instance(12L, 'Acme', 2L, false, [url: 'https://b', token: 't'])

        expect:
        global.visibleTo(2L) && global.visibleTo(3L) && global.visibleTo(1L)
        acme.visibleTo(2L)
        !acme.visibleTo(3L)
        !acme.visibleTo(1L)
        !acme.visibleTo(null)
    }

    def 'old plugin settings count as one more Argo CD until they are moved'() {
        given:
        ArgoCdSettings old = new ArgoCdSettings(url: 'https://old:30443', token: 'o', verifyTls: false, defaultProject: 'default')
        ArgoInstance moved = Instances.instance(11L, 'Prod', 1L, true, [url: 'https://old:30443', token: 'o'])
        ArgoInstance other = Instances.instance(12L, 'Lab', 1L, true, [url: 'https://lab:30443', token: 'l'])

        expect:
        Instances.withLegacy([other], old)*.id == [12L, Instances.LEGACY_ID]
        Instances.withLegacy([other], old).find { it.id == Instances.LEGACY_ID }.master
        Instances.withLegacy([moved, other], old)*.id == [11L, 12L]
        Instances.withLegacy([other], new ArgoCdSettings())*.id == [12L]
    }
}
