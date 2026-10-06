package com.morpheuslab.argocd

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import groovy.util.logging.Slf4j

/** What the plugin knows about one Morpheus cluster: its nodes, its API access, and its Argo CD matches. */
@Slf4j
class ClusterScope {

    MorpheusContext morpheus
    ComputeServerGroup cluster

    static ClusterScope of(MorpheusContext morpheus, Long clusterId) {
        ComputeServerGroup c = null
        try { c = morpheus.services.cluster.get(clusterId) } catch (Throwable t) { log.warn("ArgoCD: cluster ${clusterId} lookup failed: ${t}") }
        c ? new ClusterScope(morpheus: morpheus, cluster: c) : null
    }

    boolean isKubernetes() {
        String code = (cluster?.type?.code ?: '').toLowerCase()
        ['kube', 'hks', 'mks', 'k8s'].any { code.contains(it) }
    }

    ClusterMatcher matcher(ArgoCdSettings s, ArgoCdClient client) {
        new ClusterMatcher(clusterApiUrl: cluster.serviceUrl, clusterHosts: hosts(), argoUrl: s.url,
            client: client, probe: probe())
    }

    /** Proved matches, shared with the tab through the matcher cache. */
    List<ClusterMatcher.Match> matches(ArgoCdSettings s, ArgoCdClient client, List<ArgoApp> apps) {
        matcher(s, client).cachedMatch(cluster.id, ClusterPanel.argoClusters(client), apps)
    }

    K8sProbe probe() { new K8sProbe(apiUrl: cluster.serviceUrl, token: token()) }

    /** The token Morpheus stores for the cluster API (HKS and external clusters). */
    String token() {
        String t = cluster.serviceToken
        if (!t && cluster.id) {
            try { t = morpheus.services.cluster.get(cluster.id)?.serviceToken } catch (Throwable ignored) { }
        }
        if (!t && cluster.serviceConfig) {
            def m = cluster.serviceConfig =~ /(?m)^\s*token:\s*"?([^"\s]+)"?/
            if (m.find()) t = m.group(1)
        }
        t
    }

    /** Node IPs + API host, used to recognise an Argo CD running inside this cluster. */
    List<String> hosts() {
        List<String> hosts = []
        try {
            morpheus.services.computeServer.list(new DataQuery().withFilter('serverGroup.id', cluster.id)).each { ComputeServer s ->
                [s.internalIp, s.externalIp].findAll().each { hosts << (it as String) }
            }
        } catch (Throwable t) {
            log.warn("ArgoCD: could not list nodes of cluster ${cluster.id}: ${t}")
        }
        String api = ClusterPanel.host(cluster.serviceUrl)
        if (api) hosts << api
        hosts.unique()
    }
}
