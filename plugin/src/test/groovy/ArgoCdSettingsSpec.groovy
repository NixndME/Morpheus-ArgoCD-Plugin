import com.morpheuslab.argocd.ArgoCdSettings
import spock.lang.Specification

class ArgoCdSettingsSpec extends Specification {
    def 'parses settings, trims trailing slash, verifyTls defaults on'() {
        when:
        def s = ArgoCdSettings.parse('{"argocdUrl":"https://argo.lab/ ","argocdToken":"abc"}')
        then:
        s.url == 'https://argo.lab'
        s.token == 'abc'
        s.configured
        s.verifyTls
    }

    def 'cleared fields are unconfigured; off disables TLS verify'() {
        when:
        def s = ArgoCdSettings.parse('{"argocdUrl":"","argocdToken":"","argocdVerifyTls":"off"}')
        then:
        !s.configured
        !s.verifyTls
    }
}
