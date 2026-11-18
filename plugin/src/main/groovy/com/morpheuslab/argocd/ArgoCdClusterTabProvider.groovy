package com.morpheuslab.argocd

import com.morpheusdata.core.AbstractClusterTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ContentSecurityPolicy
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * The "Argo CD" tab on a Kubernetes cluster page. Server-rendered HTML only; every action is a
 * CSRF-protected form posting to ArgoCdController.
 */
@Slf4j
class ArgoCdClusterTabProvider extends AbstractClusterTabProvider {

    /** renderTemplate() is not handed the user; show() runs first on the same request thread. */
    private static final ThreadLocal<User> CURRENT_USER = new ThreadLocal<>()

    Plugin plugin
    MorpheusContext morpheus

    ArgoCdClusterTabProvider(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    @Override
    String getCode() { 'argocd-cluster-tab' }

    @Override
    String getName() { 'Argo CD' }

    @Override
    MorpheusContext getMorpheus() { morpheus }

    @Override
    Plugin getPlugin() { plugin }

    /** renderTemplate() is not handed the tenant either. */
    private static final ThreadLocal<Long> CURRENT_ACCOUNT = new ThreadLocal<>()

    /**
     * Shown where an Argo CD the user's tenant may use manages at least one app (or runs on this cluster). If an
     * Argo CD is broken (not set up, unreachable, token rejected) full-access users still see the tab with the reason.
     */
    @Override
    Boolean show(ComputeServerGroup cluster, User user, Account account) {
        CURRENT_USER.set(user)
        Long accountId = account?.id ?: Instances.accountOf(morpheus, user)?.id
        CURRENT_ACCOUNT.set(accountId)
        ClusterScope scope = new ClusterScope(morpheus: morpheus, cluster: cluster)
        if (!scope.kubernetes || !Access.canRead(user)) return false
        try {
            List<ArgoInstance> mine = Instances.visibleTo(morpheus, plugin, accountId)
            if (!mine) return Access.canManage(user)
            for (ArgoInstance inst : mine) {
                ArgoCdSettings s = inst.settings()
                ArgoCdClient client = ArgoCdClient.of(s)
                ArgoResult apps = client.listApplications()
                if (!apps.ok) { if (Access.canManage(user)) return true; continue }
                List<ArgoApp> all = ((apps.data?.items ?: []) as List).collect { ArgoApp.parse(it as Map) }
                if (!scope.matches(s, client, all).isEmpty()) return true
            }
            return false
        } catch (Throwable t) {
            log.warn("ArgoCD: show() failed for cluster ${cluster?.id}: ${t}")
            return false
        }
    }

    @Override
    HTMLResponse renderTemplate(ComputeServerGroup cluster) {
        User user = CURRENT_USER.get()
        Long accountId = CURRENT_ACCOUNT.get()
        CURRENT_USER.remove()
        CURRENT_ACCOUNT.remove()
        ViewModel<Map> model = new ViewModel<>()
        model.object = [sections: sections(cluster, user, accountId)]
        return getRenderer().renderTemplate('hbs/cluster/argocd-tab', model)
    }

    /** One panel per Argo CD that manages this cluster (or has a problem the manager should see). */
    List<ClusterPanel> sections(ComputeServerGroup cluster, User user, Long accountId) {
        ClusterScope scope = new ClusterScope(morpheus: morpheus, cluster: cluster)
        List<ArgoInstance> mine = []
        try { mine = Instances.visibleTo(morpheus, plugin, accountId) } catch (Throwable t) { log.warn("ArgoCD: listing Argo CDs failed: ${t}") }
        Map flash = PanelState.takeFlash(user?.id, cluster.id)
        Map base = [clusterId: cluster.id, clusterName: cluster.name, clusterApiUrl: cluster.serviceUrl,
                    clusterHosts: scope.hosts(), probe: scope.probe(), cached: true, accessLevel: Access.level(user),
                    state: PanelState.get(user?.id, cluster.id), csrf: Csrf.token()]
        if (!mine) return [panel(base + [settings: new ArgoCdSettings(), flash: flash], cluster)]
        List<ClusterPanel> out = []
        int offset = 0
        mine.eachWithIndex { ArgoInstance inst, int i ->
            ClusterPanel p = panel(base + [settings: inst.settings(), instanceId: String.valueOf(inst.id), instanceLabel: inst.label,
                                           multi: mine.size() > 1, sfx: i == 0 ? '' : "-s${i}".toString(), indexOffset: offset], cluster)
            boolean useful = p.state != 'ok' ? Access.canManage(user) : !p.matches.isEmpty()
            if (useful) { out << p; offset += p.appViews.size() }
        }
        if (!out) return [panel(base + [settings: mine[0].settings(), instanceId: String.valueOf(mine[0].id), flash: flash], cluster)]
        out[0].flash = flash
        if (out.size() == 1) out[0].multi = false
        // id suffixes follow the shown sections, so the first one keeps the ids of a single Argo CD
        out.eachWithIndex { ClusterPanel p, int i -> p.sfx = i == 0 ? '' : "-s${i}".toString() }
        out
    }

    private ClusterPanel panel(Map ctx, ComputeServerGroup cluster) {
        try {
            ClusterPanel.build(ctx)
        } catch (Throwable t) {
            log.error("ArgoCD: panel failed for cluster ${cluster?.id}", t)
            new ClusterPanel(clusterId: cluster.id, clusterName: cluster.name, state: 'error',
                stateMessage: "The Argo CD panel failed to render: ${t.class.simpleName}: ${t.message}")
        }
    }

    @Override
    ContentSecurityPolicy getContentSecurityPolicy() {
        new ContentSecurityPolicy(connectSrc: "'self'")
    }
}
