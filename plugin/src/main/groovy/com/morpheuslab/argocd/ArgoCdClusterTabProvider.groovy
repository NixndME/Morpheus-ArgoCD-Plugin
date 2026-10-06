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

    /**
     * Shown only where Argo CD manages at least one app on this cluster. If Argo CD itself is broken
     * (not configured, unreachable, token rejected) full-access users still see the tab with the reason.
     */
    @Override
    Boolean show(ComputeServerGroup cluster, User user, Account account) {
        CURRENT_USER.set(user)
        ClusterScope scope = new ClusterScope(morpheus: morpheus, cluster: cluster)
        if (!scope.kubernetes || !Access.canRead(user)) return false
        try {
            ArgoCdSettings s = ArgoCdSettings.read(morpheus, plugin)
            if (!s.configured) return Access.canManage(user)
            ArgoCdClient client = ArgoCdClient.of(s)
            ArgoResult apps = client.listApplications()
            if (!apps.ok) return Access.canManage(user)
            List<ArgoApp> all = ((apps.data?.items ?: []) as List).collect { ArgoApp.parse(it as Map) }
            return !scope.matches(s, client, all).isEmpty()
        } catch (Throwable t) {
            log.warn("ArgoCD: show() failed for cluster ${cluster?.id}: ${t}")
            return false
        }
    }

    @Override
    HTMLResponse renderTemplate(ComputeServerGroup cluster) {
        User user = CURRENT_USER.get()
        CURRENT_USER.remove()
        ClusterScope scope = new ClusterScope(morpheus: morpheus, cluster: cluster)
        ClusterPanel panel
        try {
            panel = ClusterPanel.build(
                clusterId: cluster.id, clusterName: cluster.name, clusterApiUrl: cluster.serviceUrl,
                clusterHosts: scope.hosts(), probe: scope.probe(), cached: true,
                accessLevel: Access.level(user),
                settings: ArgoCdSettings.read(morpheus, plugin),
                state: PanelState.get(user?.id, cluster.id),
                flash: PanelState.takeFlash(user?.id, cluster.id),
                csrf: Csrf.token())
        } catch (Throwable t) {
            log.error("ArgoCD: panel failed for cluster ${cluster?.id}", t)
            panel = new ClusterPanel(clusterId: cluster.id, clusterName: cluster.name, state: 'error',
                stateMessage: "The Argo CD panel failed to render: ${t.class.simpleName}: ${t.message}")
        }
        ViewModel<ClusterPanel> model = new ViewModel<>()
        model.object = panel
        return getRenderer().renderTemplate('hbs/cluster/argocd-tab', model)
    }

    @Override
    ContentSecurityPolicy getContentSecurityPolicy() {
        new ContentSecurityPolicy(connectSrc: "'self'")
    }
}
