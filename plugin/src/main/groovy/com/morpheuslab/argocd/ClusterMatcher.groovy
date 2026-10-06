package com.morpheuslab.argocd

import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/**
 * Works out which Argo CD destinations are this Morpheus cluster. Names are never trusted.
 * Proof, best first: same object uid, same cluster CA, then same API URL.
 * Only destinations that have apps count.
 */
@Slf4j
class ClusterMatcher {

    static final long CACHE_MILLIS = 60_000L
    private static final Map<String, Map> CACHE = new ConcurrentHashMap<>()
    private static final Map<String, Map> SAMPLES = new ConcurrentHashMap<>()

    static class Match {
        String server
        String name
        String method
        int apps
        String getMethodText() {
            [uid: 'verified by resource UID', ca: 'verified by cluster CA', url: 'matched by API URL',
             host: 'Argo CD runs on this cluster'][method] ?: method
        }
    }

    String clusterApiUrl
    List<String> clusterHosts = []
    String argoUrl
    K8sProbe probe
    ArgoCdClient client

    /** Matched destinations (usually one). Empty = Argo CD manages nothing on this cluster. */
    List<Match> match(List<Map> argoClusters, List<ArgoApp> apps) {
        Map<String, String> nameToServer = argoClusters.collectEntries { [(it.name as String): norm(it.server as String)] }
        Map<String, List<ArgoApp>> byServer = apps.groupBy { a -> norm(a.destServer) ?: nameToServer[a.destName] }
        byServer.remove(null)
        List<Match> found = []
        byServer.each { String server, List<ArgoApp> destApps ->
            String method = prove(server, destApps)
            if (method) {
                String name = argoClusters.find { norm(it.server as String) == server }?.name
                found << new Match(server: server, name: name, method: method, apps: destApps.size())
            }
        }
        found
    }

    /** Cached per cluster so page loads stay fast; show() and the tab share one result. */
    List<Match> cachedMatch(Long clusterId, List<Map> argoClusters, List<ArgoApp> apps) {
        String key = "${clusterId}|${argoUrl}".toString()
        Map hit = CACHE[key]
        if (hit && System.currentTimeMillis() - (hit.at as long) < CACHE_MILLIS) return hit.matches as List<Match>
        List<Match> m = match(argoClusters, apps)
        CACHE[key] = [at: System.currentTimeMillis(), matches: m]
        m
    }

    static void clearCache() { CACHE.clear(); SAMPLES.clear() }

    static boolean targets(ArgoApp a, List<Match> matches) {
        matches.any { m -> (a.destServer && norm(a.destServer) == m.server) || (a.destName && a.destName == m.name) }
    }

    private String ourCa
    private boolean ourCaRead

    private String ourCaKeyId() {
        if (!ourCaRead) { ourCa = probe.caKeyId(clusterApiUrl); ourCaRead = true }
        ourCa
    }

    private String prove(String server, List<ArgoApp> destApps) {
        K8sProbe.Proof uid = uidProof(server, destApps)
        if (uid == K8sProbe.Proof.MATCH) return 'uid'
        if (uid == K8sProbe.Proof.MISMATCH) return null
        if (server != norm(ClusterPanel.IN_CLUSTER) && probe) {
            String ours = ourCaKeyId()
            String theirs = probe.caKeyId(server)
            if (ours && theirs) return ours == theirs ? 'ca' : null
        }
        if (norm(clusterApiUrl) == server) return 'url'
        if (server == norm(ClusterPanel.IN_CLUSTER) && clusterHosts.contains(ClusterPanel.host(argoUrl))) return 'host'
        null
    }

    /** Look up to 3 of the destination's objects in this cluster; the first clear answer wins. */
    private K8sProbe.Proof uidProof(String server, List<ArgoApp> destApps) {
        if (!probe?.usable || !client) return K8sProbe.Proof.UNKNOWN
        for (Map n : sampleObjects(server, destApps)) {
            K8sProbe.Proof p = probe.objectUid(n.kind as String, n.namespace as String, n.name as String, n.uid as String)
            if (p != K8sProbe.Proof.UNKNOWN) return p
        }
        K8sProbe.Proof.UNKNOWN
    }

    /** Objects (kind, namespace, name, uid) Argo CD deployed to a destination. Shared by all clusters for a minute. */
    private List<Map> sampleObjects(String server, List<ArgoApp> destApps) {
        String key = "${argoUrl}|${server}|${destApps*.name.sort().join(',')}".toString()
        Map hit = SAMPLES[key]
        if (hit && System.currentTimeMillis() - (hit.at as long) < CACHE_MILLIS) return hit.objects as List<Map>
        List<Map> objects = []
        for (ArgoApp a : destApps.take(2)) {
            ArgoResult tree = client.resourceTree(a.name)
            if (!tree.ok) continue
            objects.addAll(((tree.data?.nodes ?: []) as List<Map>)
                .findAll { it.uid && K8sProbe.KINDS.containsKey(it.kind) }
                .sort { K8sProbe.PREFERRED.indexOf(it.kind) }
                .take(3)
                .collect { [kind: it.kind, namespace: it.namespace, name: it.name, uid: it.uid] })
            if (objects) break
        }
        SAMPLES[key] = [at: System.currentTimeMillis(), objects: objects]
        objects
    }

    static String norm(String url) { ClusterPanel.norm(url) }
}
