import com.morpheuslab.argocd.ArgoCdSettings
import com.morpheuslab.argocd.ArgoResult
import com.morpheuslab.argocd.SetupPage
import spock.lang.Specification

class SetupPageSpec extends Specification {

    static final Map HKS = [id: 5L, name: 'argo-test', host: '10.0.0.5', clusterStatus: 'ok']

    def 'a cluster without Argo CD can get it while the plugin has no connection'() {
        when:
        Map r = SetupPage.row(HKS, [state: 'none'], null, null)

        then:
        r.status == 'Not installed'
        r.chip == 'unknown'
        r.canInstall
        !r.canRemove && !r.canConnect && !r.inUse
    }

    def 'no Install while the plugin already uses an Argo CD'() {
        expect:
        !SetupPage.row(HKS, [state: 'none'], null, 'https://10.0.0.9:30443').canInstall
    }

    def 'our running Argo CD that the plugin uses can be removed but not connected again'() {
        when:
        Map r = SetupPage.row(HKS, [state: 'ours', ready: true, version: 'v3.6.1', url: 'https://10.0.0.5:30443'], null, 'https://10.0.0.5:30443')

        then:
        r.status == 'Installed'
        r.chip == 'ok'
        r.version == 'v3.6.1'
        r.inUse
        r.canRemove
        !r.canConnect
        !r.canInstall
    }

    def 'our Argo CD reached on a worker address counts as in use'() {
        given:
        Map c = HKS + [hosts: ['10.0.0.6', '10.0.0.5']]

        expect:
        SetupPage.row(c, [state: 'ours', ready: true, url: 'https://10.0.0.6:30443'], null, 'https://10.0.0.6:30443').inUse
        SetupPage.row(HKS, [state: 'ours', ready: true, url: 'https://10.0.0.6:30443'], null, 'https://10.0.0.6:30443').inUse
        SetupPage.row(c, [state: 'other'], null, 'https://10.0.0.6:30443').inUse
        !SetupPage.row(c, [state: 'other'], null, 'https://10.0.0.9:30443').inUse
    }

    def 'the Remove box only accepts the exact cluster name (browser pattern, unicode safe)'() {
        expect:
        SetupPage.row(HKS + [name: name], [state: 'ours'], null, null).confirmPattern == pattern

        where:
        name               || pattern
        'argo-test'        || 'argo-test'
        'prod.eu (1)+'     || 'prod\\.eu \\(1\\)\\+'
        'a[b]|c$'          || 'a\\[b\\]\\|c\\$'
    }

    def 'an Argo CD installed for another integration is not removed or connected from this page'() {
        when:
        Map other = SetupPage.row(HKS, [state: 'ours', ready: true, owner: 12L, url: 'https://10.0.0.6:30443'], null, null, 11L)
        Map mine = SetupPage.row(HKS, [state: 'ours', ready: true, owner: 11L, url: 'https://10.0.0.6:30443'], null, null, 11L)
        Map old = SetupPage.row(HKS, [state: 'ours', ready: true, url: 'https://10.0.0.6:30443'], null, null, 11L)

        then:
        !other.canRemove && !other.canConnect && !other.canInstall
        other.status == 'Installed'
        other.detail == 'Installed for another Argo CD integration'
        mine.canRemove && mine.canConnect
        old.canRemove && old.canConnect
    }

    def 'an install whose integration was deleted (plugin reinstalled) has no owner any more'() {
        expect:
        SetupPage.liveOwner([state: 'ours', owner: 12L], [11L, 13L]).owner == null
        SetupPage.liveOwner([state: 'ours', owner: 12L], [12L]).owner == 12L
        SetupPage.liveOwner([state: 'none'], [12L]) == [state: 'none']
    }

    def 'our Argo CD that the plugin does not use offers Connect'() {
        expect:
        SetupPage.row(HKS, [state: 'ours', ready: true, url: 'https://10.0.0.5:30443'], null, null).canConnect
    }

    def 'an Argo CD someone else installed is shown but never removed'() {
        when:
        Map r = SetupPage.row(HKS, [state: 'other', namespace: 'gitops', version: 'v3.5.6'], null, null)

        then:
        r.status == 'Argo CD found'
        r.detail.contains('gitops')
        !r.canRemove && !r.canInstall && !r.canConnect
    }

    def 'a running job wins over everything and blocks actions'() {
        when:
        Map r = SetupPage.row(HKS, [state: 'ours', ready: false], 'Installing', null)

        then:
        r.status == 'Installing'
        r.chip == 'info'
        !r.canInstall && !r.canRemove && !r.canConnect
    }

    def 'a cluster Morpheus is still building says so'() {
        expect:
        SetupPage.row(HKS + [clusterStatus: 'provisioning'], [state: 'error', error: 'no route'], null, null).status == 'Being built'
        SetupPage.row(HKS + [clusterStatus: 'error'], [state: 'error', error: 'Connection refused'], null, null).status == 'Cannot reach'
    }

    def 'connection summary says what the plugin talks to'() {
        given:
        ArgoCdSettings s = new ArgoCdSettings(url: 'https://10.0.0.5:30443', token: 't')

        expect:
        SetupPage.connection(new ArgoCdSettings(), null, null) == [chip: 'unknown', status: 'Not set', text: 'The plugin is not connected to an Argo CD yet.']
        SetupPage.connection(s, ArgoResult.from(200, '{"Version":"v3.6.1+abc"}'), ArgoResult.from(200, '{"loggedIn":true,"username":"morpheus"}')) ==
            [chip: 'ok', status: 'Connected', text: 'https://10.0.0.5:30443, Argo CD v3.6.1, as morpheus']
        SetupPage.connection(s, ArgoResult.from(200, '{"Version":"v3.6.1"}'), ArgoResult.from(401, '{"message":"invalid session"}')).chip == 'bad'
        SetupPage.connection(s, ArgoResult.unreachable('Connection refused'), null).status == 'Cannot reach'
    }

    def 'a token counts only when Argo CD says it is logged in (a bad token gets 200 with an empty body)'() {
        given:
        ArgoCdSettings s = new ArgoCdSettings(url: 'https://10.0.0.5:30443', token: 'bad')
        ArgoResult ver = ArgoResult.from(200, '{"Version":"v3.6.1"}')

        expect:
        SetupPage.connection(s, ver, ArgoResult.from(200, '{}')).status == 'Token rejected'
        SetupPage.connection(s, ver, ArgoResult.from(200, '{"loggedIn":false}')).status == 'Token rejected'
        SetupPage.connection(s, ver, ArgoResult.from(200, '{"loggedIn":true,"username":"morpheus"}')).status == 'Connected'
        !SetupPage.loggedIn(ArgoResult.from(200, '{}'))
        SetupPage.loggedIn(ArgoResult.from(200, '{"loggedIn":true}'))
    }
}
