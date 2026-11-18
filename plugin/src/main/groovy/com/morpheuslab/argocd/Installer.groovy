package com.morpheuslab.argocd

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Installs the bundled Argo CD on one cluster through the Kubernetes API (Morpheus' stored cluster token),
 * adds the least privilege `morpheus` account and makes its API token. Removes only what it installed.
 */
@Slf4j
class Installer {

    static final String NS = 'argocd'
    static final String MARKER = 'morpheus-argocd'
    static final String LABEL = 'app.kubernetes.io/managed-by'
    static final String SERVICE = 'argocd-server-morpheus'
    static final String TOKEN_SECRET = 'morpheus-argocd-token'
    static final String ADMIN_SECRET = 'argocd-initial-admin-secret'
    static final int PORT = 30443
    static final String CRD_PATH = '/apis/apiextensions.k8s.io/v1/customresourcedefinitions'
    static final String RBAC_PATH = '/apis/rbac.authorization.k8s.io/v1'
    static final List<String> CRDS = ['applications.argoproj.io', 'applicationsets.argoproj.io', 'appprojects.argoproj.io']
    static final List<String> CLUSTER_KINDS = ['CustomResourceDefinition', 'ClusterRole', 'ClusterRoleBinding', 'Namespace']
    static final String POLICY = '''p, role:morpheus, applications, *, */*, allow
p, role:morpheus, clusters, get, *, allow
p, role:morpheus, projects, get, *, allow
p, role:morpheus, logs, get, */*, allow
g, morpheus, role:morpheus
'''
    static final int SERVER_WAIT_MINUTES = 45

    /** Network calls to Argo CD itself; tests swap them. */
    static Closure<Boolean> reach = { String url -> answers(url) }
    static Closure pause = { long ms -> Thread.sleep(ms) }
    static Closure<Map> argoCall = { String method, String url, Map body, String bearer, X509Certificate cert -> argo(method, url, body, bearer, cert) }

    private static String manifestText
    private static String version

    // ---------- the bundled release ----------

    /** The bundled Argo CD objects, a fresh copy for each caller. */
    static List<Map> manifest() {
        (List<Map>) new JsonSlurper().parseText(text())
    }

    private static synchronized String text() {
        if (manifestText == null) manifestText = Installer.getResourceAsStream('/argocd/install.json').getText('UTF-8')
        manifestText
    }

    /** Argo CD version of the bundled release, for example v3.6.1. */
    static synchronized String bundledVersion() {
        if (version == null) version = imageTag(manifest().find { it.kind == 'Deployment' && it.metadata.name == 'argocd-server' } as Map)
        version
    }

    /** The objects to apply: namespaces set, our label on all, our account and role, a NodePort for Morpheus and the admin. */
    static List<Map> prepare(List<Map> objects, String url) {
        int port = (new URI(url).port > 0 ? new URI(url).port : PORT)
        List<Map> out = (List<Map>) new JsonSlurper().parseText(JsonOutput.toJson(objects))
        out << nodePort(port)
        out.each { Map o ->
            if (!(o.kind in CLUSTER_KINDS)) o.metadata.namespace = NS
            o.metadata.labels = ((o.metadata.labels ?: [:]) as Map) + [(LABEL): MARKER]
            if (o.kind == 'ConfigMap' && o.metadata.name == 'argocd-cm') {
                o.data = ((o.data ?: [:]) as Map) + ['accounts.morpheus': 'apiKey', url: url]
            }
            if (o.kind == 'ConfigMap' && o.metadata.name == 'argocd-rbac-cm') {
                o.data = ((o.data ?: [:]) as Map) + ['policy.csv': POLICY]
            }
        }
        out
    }

    static Map nodePort(Integer port) {
        Map p = [name: 'https', port: 443, targetPort: 8080, protocol: 'TCP']
        if (port) p.nodePort = port
        [apiVersion: 'v1', kind: 'Service', metadata: [name: SERVICE, namespace: NS, labels: ['app.kubernetes.io/part-of': 'argocd', (LABEL): MARKER]],
         spec: [type: 'NodePort', selector: ['app.kubernetes.io/name': 'argocd-server'], ports: [p]]]
    }

    // ---------- what the cluster has ----------

    /** [state: none | ours | other | error, namespace, version, ready, url, error]. */
    static Map status(Kube kube) {
        Map r = kube.get('/apis/apps/v1/deployments?labelSelector=' + enc('app.kubernetes.io/name=argocd-server'))
        if (r.error) return [state: 'error', error: r.error]
        List items = (r.data?.items ?: []) as List
        if (!items) {
            // a stopped install: our marker without a server yet
            Map m = kube.get("/api/v1/namespaces/${NS}/configmaps/${MARKER}")
            return m.status == 200 ? [state: 'ours', namespace: NS, version: '', ready: false, url: m.data?.data?.url, owner: owner(m)] : [state: 'none']
        }
        Map dep = items[0] as Map
        String ns = dep.metadata.namespace
        Map marker = kube.get("/api/v1/namespaces/${ns}/configmaps/${MARKER}")
        [state: marker.status == 200 ? 'ours' : 'other', namespace: ns, version: imageTag(dep),
         ready: ((dep.status?.readyReplicas ?: 0) as int) > 0, url: marker.status == 200 ? marker.data?.data?.url : null,
         owner: marker.status == 200 ? owner(marker) : null]
    }

    private static Long owner(Map marker) { String o = marker.data?.data?.owner; o?.isLong() ? o as Long : null }

    /** Argo CD parts on the cluster that this plugin did not make, or null. */
    static String conflict(Kube kube) {
        boolean ours = kube.get("/api/v1/namespaces/${NS}/configmaps/${MARKER}").status == 200
        if (!ours && kube.get("/api/v1/namespaces/${NS}").status == 200) return "The cluster already has a namespace ${NS}. Argo CD may be installed there."
        for (String name : CRDS) {
            Map c = kube.get("${CRD_PATH}/${name}")
            if (c.status == 200 && c.data?.metadata?.labels?.get(LABEL) != MARKER) return 'The cluster already has the Argo CD resource types from another install.'
        }
        null
    }

    /** First admin password Argo CD made, or null when the admin changed it and deleted the secret. */
    static String adminPassword(Kube kube) {
        Map r = kube.get("/api/v1/namespaces/${NS}/secrets/${ADMIN_SECRET}")
        String b64 = r.status == 200 ? r.data?.data?.password : null
        b64 ? decode(b64) : null
    }

    /** URL and token saved by the install, so the settings can be set again later. */
    static Map savedToken(Kube kube) {
        Map r = kube.get("/api/v1/namespaces/${NS}/secrets/${TOKEN_SECRET}")
        Map d = r.status == 200 ? (r.data?.data ?: [:]) as Map : [:]
        d.url && d.token ? [url: decode(d.url), token: decode(d.token)] : null
    }

    /** The saved token, or a new one when the install stopped before it made one. Returns [url, token] or [error]. */
    static Map connectToken(Kube kube) {
        Map saved = savedToken(kube)
        if (saved) return saved
        Map st = status(kube)
        if (st.state != 'ours' || !st.ready || !st.url) return [error: 'Argo CD from this plugin is not running on this cluster.']
        Map t = makeToken(kube, st.url as String)
        t.error ? t : storeToken(kube, st.url as String, t.token as String)
    }

    // ---------- install ----------

    /**
     * Install, then find the node address Morpheus reaches Argo CD on. `nodeHosts` are the node IPs Morpheus knows
     * (workers first), `apiHost` the cluster API host. Returns [url, token] or [error].
     */
    static Map install(Kube kube, List<String> nodeHosts, String apiHost, Closure say, Long owner = null) {
        String clash = conflict(kube)
        if (clash) return [error: clash]
        say("Creating namespace ${NS}")
        Map r = kube.apply([apiVersion: 'v1', kind: 'Namespace', metadata: [name: NS, labels: [(LABEL): MARKER]]])
        if (r.error) return [error: "Could not create the namespace: ${r.error}"]
        // the marker goes first, so Remove can clean up an install that stops halfway
        r = kube.apply(marker('', owner))
        if (r.error) return [error: "Could not save the plugin marker: ${r.error}"]

        Integer port = openPort(kube, say)
        if (port == null) return [error: 'Could not open a port for Argo CD on the cluster nodes.']
        List<String> hosts = candidates(nodeHosts, nodeAddresses(kube), apiHost)
        if (!hosts) return [error: 'Morpheus knows no address for the cluster nodes.']
        String url = "https://${hosts[0]}:${port}"
        kube.apply(marker(url, owner))

        List<Map> objects = prepare(manifest(), url)
        List<Map> crds = objects.findAll { it.kind == 'CustomResourceDefinition' }
        say("Adding ${crds.size()} Argo CD resource types")
        for (Map c : crds) {
            r = kube.apply(c)
            if (r.error) return [error: "Could not add ${c.metadata.name}: ${r.error}"]
        }
        for (int i = 0; i < 30 && !crds.every { established(kube, it.metadata.name as String) }; i++) pause(2000)

        List<Map> rest = afterTypes(objects)
        say("Creating ${rest.size()} Argo CD objects")
        for (Map o : rest) {
            r = kube.apply(o)
            if (r.error) return [error: "Could not create ${o.kind} ${o.metadata.name}: ${r.error}"]
        }

        say('Waiting for Argo CD to start. The nodes download the images first, this can take a while.')
        if (!waitForServer(kube, say)) return [error: "Argo CD did not start within ${SERVER_WAIT_MINUTES} minutes. Check the pods in namespace ${NS}, then press Connect."]

        say("Finding the node address Morpheus reaches Argo CD on (trying ${hosts.join(', ')})")
        String reached = null
        for (int i = 0; i < 6 && !reached; i++) {
            reached = pickUrl(hosts, port, reach)
            if (!reached) pause(5000)
        }
        if (!reached) return [error: "Argo CD runs, but Morpheus can not reach port ${port} on any node (${hosts.join(', ')}). Open the port to Morpheus, then press Connect."]
        if (reached != url) {
            url = reached
            kube.apply(prepare(manifest(), url).find { it.kind == 'ConfigMap' && it.metadata.name == 'argocd-cm' })
            kube.apply(marker(url, owner))
        }
        say("Argo CD answers at ${url}")

        say('Making the API token for the plugin')
        Map t = makeToken(kube, url)
        t.error ? t : storeToken(kube, url, t.token as String)
    }

    /** Objects applied after the resource types. Our NodePort exists already (openPort): creating it again fails on the port before the name. */
    static List<Map> afterTypes(List<Map> objects) {
        objects.findAll { it.kind != 'CustomResourceDefinition' && !(it.kind == 'Service' && it.metadata.name == SERVICE) }
    }

    /** `owner` is the Argo CD integration the install belongs to. */
    static Map marker(String url, Long owner = null) {
        [apiVersion: 'v1', kind: 'ConfigMap', metadata: [name: MARKER, namespace: NS, labels: [(LABEL): MARKER]],
         data: [url: url, version: bundledVersion(), installedAt: String.valueOf(System.currentTimeMillis()), owner: owner ? String.valueOf(owner) : '']]
    }

    private static Map storeToken(Kube kube, String url, String token) {
        Map r = kube.apply([apiVersion: 'v1', kind: 'Secret', metadata: [name: TOKEN_SECRET, namespace: NS, labels: [(LABEL): MARKER]],
                            type: 'Opaque', stringData: [url: url, token: token]])
        r.error ? [error: "Could not save the token in the cluster: ${r.error}"] : [url: url, token: token]
    }

    /** Our NodePort service on 30443, or on a port Kubernetes picks when 30443 is taken. */
    static Integer openPort(Kube kube, Closure say) {
        Map cur = kube.get("/api/v1/namespaces/${NS}/services/${SERVICE}")
        if (cur.status == 200) return cur.data?.spec?.ports?.getAt(0)?.nodePort as Integer
        Map r = kube.post("/api/v1/namespaces/${NS}/services", nodePort(PORT))
        if (r.status == 422 || r.status == 409) {
            say("Port ${PORT} is in use, letting Kubernetes pick a free one")
            r = kube.post("/api/v1/namespaces/${NS}/services", nodePort(null))
        }
        if (r.error) { log.warn("ArgoCD: NodePort failed: ${r.error}"); return null }
        r.data?.spec?.ports?.getAt(0)?.nodePort as Integer
    }

    static boolean waitForServer(Kube kube, Closure say) {
        long end = System.currentTimeMillis() + SERVER_WAIT_MINUTES * 60_000L
        long nextNote = System.currentTimeMillis() + 120_000L
        while (System.currentTimeMillis() < end) {
            Map d = kube.get("/apis/apps/v1/namespaces/${NS}/deployments/argocd-server")
            if (((d.data?.status?.readyReplicas ?: 0) as int) > 0) return true
            if (System.currentTimeMillis() > nextNote) {
                say("Still waiting: ${podsReady(kube)}")
                nextNote = System.currentTimeMillis() + 120_000L
            }
            pause(5000)
        }
        false
    }

    /** For example "5 of 7 Argo CD pods running". */
    static String podsReady(Kube kube) {
        List pods = (kube.get("/api/v1/namespaces/${NS}/pods").data?.items ?: []) as List
        int up = pods.count { Map p -> (p.status?.containerStatuses ?: []).every { it.ready } && p.status?.containerStatuses } as int
        "${up} of ${pods.size()} Argo CD pods running"
    }

    /**
     * Log in once with the first admin password and make a token for the `morpheus` account. The password is sent
     * only to a server that shows the certificate Argo CD stored in the cluster.
     */
    static Map makeToken(Kube kube, String url) {
        String pw = adminPassword(kube)
        if (!pw) return [error: "The first admin password is gone (secret ${ADMIN_SECRET}), so the plugin can not make its token. Make one for the account morpheus in Argo CD and save it under Connection."]
        Map login = [:]
        for (int i = 0; i < 30; i++) {
            X509Certificate cert = serverCert(kube)
            login = cert ? argoCall('POST', url + '/api/v1/session', [username: 'admin', password: pw], null, cert) : [status: 0, error: 'Argo CD has not stored its certificate yet']
            if (login.status == 200 || login.status == 401) break
            pause(4000)
        }
        if (login.status != 200) return [error: "Could not log in to Argo CD as admin: ${login.error ?: 'HTTP ' + login.status}"]
        Map t = argoCall('POST', url + '/api/v1/account/morpheus/token', [id: "morpheus-plugin-${System.currentTimeMillis()}".toString()],
            login.data?.token as String, serverCert(kube))
        t.data?.token ? [token: t.data.token] : [error: "Argo CD did not make the token: ${t.error ?: 'HTTP ' + t.status}"]
    }

    /** The certificate argocd-server made for itself (secret argocd-secret), read over the cluster API. */
    static X509Certificate serverCert(Kube kube) {
        Map r = kube.get("/api/v1/namespaces/${NS}/secrets/argocd-secret")
        String b64 = r.status == 200 ? r.data?.data?.get('tls.crt') : null
        if (!b64) return null
        try {
            (X509Certificate) CertificateFactory.getInstance('X.509').generateCertificate(new ByteArrayInputStream(b64.decodeBase64()))
        } catch (Exception e) {
            log.warn("ArgoCD: could not read the Argo CD certificate: ${e}")
            null
        }
    }

    // ---------- remove ----------

    /** Remove an Argo CD this plugin installed. The apps' workloads stay; only Argo CD goes. Returns null or an error. */
    static String remove(Kube kube, Closure say) {
        Map st = status(kube)
        if (st.state != 'ours') return 'Argo CD on this cluster was not installed by this plugin, so it stays.'
        if (st.namespace != NS) return "Only an Argo CD in namespace ${NS} is removed by this plugin."
        say('Letting go of the Argo CD applications (their workloads keep running)')
        // application sets first, or their controller makes the applications again
        for (String kind : ['applicationsets', 'applications', 'appprojects']) {
            Map list = kube.get("/apis/argoproj.io/v1alpha1/namespaces/${NS}/${kind}")
            if (list.error && list.status != 404) return "Could not list the Argo CD ${kind}: ${list.error}"
            for (Map o : ((list.data?.items ?: []) as List<Map>)) {
                String p = "/apis/argoproj.io/v1alpha1/namespaces/${NS}/${kind}/${o.metadata.name}"
                if (o.metadata.finalizers) {
                    o.metadata.finalizers = []
                    kube.put(p, o)
                }
                kube.delete(p)
            }
        }
        String ours = '?labelSelector=' + enc("${LABEL}=${MARKER}")
        say('Deleting the Argo CD cluster roles')
        kube.delete("${RBAC_PATH}/clusterrolebindings${ours}")
        kube.delete("${RBAC_PATH}/clusterroles${ours}")
        say("Deleting namespace ${NS}")
        kube.delete("/api/v1/namespaces/${NS}")
        kube.delete("${CRD_PATH}${ours}")
        say('Argo CD is removed')
        null
    }

    // ---------- which address reaches Argo CD ----------

    /** Node addresses Kubernetes reports: workers first, ExternalIP before InternalIP. */
    static List<String> nodeAddresses(Kube kube) {
        List<Map> nodes = ((kube.get('/api/v1/nodes').data?.items ?: []) as List<Map>)
        nodes.sort(false) { Map n -> isControlPlane(n) ? 1 : 0 }.collectMany { Map n ->
            List<Map> a = (n.status?.addresses ?: []) as List<Map>
            (a.findAll { it.type == 'ExternalIP' } + a.findAll { it.type == 'InternalIP' })*.address
        }.findAll().collect { it as String }.unique()
    }

    static boolean isControlPlane(Map node) {
        Map labels = (node.metadata?.labels ?: [:]) as Map
        labels.containsKey('node-role.kubernetes.io/control-plane') || labels.containsKey('node-role.kubernetes.io/master')
    }

    /** Addresses to try, best first: what Morpheus knows about the nodes, then Kubernetes, then the API host. */
    static List<String> candidates(List<String> fromMorpheus, List<String> fromKube, String apiHost) {
        ((fromMorpheus ?: []) + (fromKube ?: []) + [apiHost]).findAll().collect { it as String }.unique()
    }

    /** The first https://<host>:<port> that answers, or null. */
    static String pickUrl(List<String> hosts, int port, Closure<Boolean> answers) {
        hosts.collect { "https://${it}:${port}".toString() }.find { String url -> answers.call(url) }
    }

    /** Argo CD answers its version page here (no login needed, nothing secret is sent). */
    static boolean answers(String url) {
        try { (Kube.send('GET', url + '/api/version', null, null, null).status as int) == 200 } catch (Throwable ignored) { false }
    }

    // ---------- helpers ----------

    /** Why an Argo CD address must not be used (it points back at this server), or null when it is fine. */
    static String unsafeUrl(String url) {
        String host
        try { host = new URI(url).host } catch (Exception ignored) { return 'That is not a valid address.' }
        if (!host) return 'That is not a valid address.'
        try {
            InetAddress[] all = InetAddress.getAllByName(host)
            if (all.any { InetAddress a -> a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress() }) {
                return 'That address points at this server or a local network service. Use the Argo CD address on the network.'
            }
        } catch (Exception ignored) {
            return "Could not find the host ${host}."
        }
        null
    }

    static boolean established(Kube kube, String name) {
        Map r = kube.get("${CRD_PATH}/${name}")
        (r.data?.status?.conditions ?: []).any { it.type == 'Established' && it.status == 'True' }
    }

    /** A call to the Argo CD API that trusts only the given server certificate. */
    static Map argo(String method, String url, Map body, String bearer, X509Certificate pinned) {
        HttpURLConnection c = null
        try {
            c = (HttpURLConnection) new URL(url).openConnection()
            if (c instanceof HttpsURLConnection) {
                ((HttpsURLConnection) c).SSLSocketFactory = pinnedContext(pinned).socketFactory
                // the certificate is checked exactly, the self-signed one does not name the node address
                ((HttpsURLConnection) c).hostnameVerifier = { h, s -> true }
            }
            c.requestMethod = method
            c.connectTimeout = 5_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty('Accept', 'application/json')
            c.setRequestProperty('Content-Type', 'application/json')
            if (bearer) c.setRequestProperty('Authorization', "Bearer ${bearer}")
            if (body != null) {
                c.doOutput = true
                c.outputStream.withWriter('UTF-8') { it << JsonOutput.toJson(body) }
            }
            int status = c.responseCode
            String text = (status >= 400 ? c.errorStream : c.inputStream)?.getText('UTF-8') ?: ''
            Object data = null
            try { data = text ? new JsonSlurper().parseText(text) : null } catch (Exception ignored) { }
            [status: status, data: data, error: status >= 400 ? ((data instanceof Map ? data.message : null) ?: "HTTP ${status}") : null]
        } catch (Throwable t) {
            [status: 0, error: "${t.class.simpleName}: ${t.message}"]
        } finally {
            c?.disconnect()
        }
    }

    static SSLContext pinnedContext(X509Certificate pinned) {
        TrustManager tm = new X509TrustManager() {
            void checkClientTrusted(X509Certificate[] chain, String authType) { throw new CertificateException('not used') }
            void checkServerTrusted(X509Certificate[] chain, String authType) {
                if (!chain || chain[0].encoded != pinned.encoded) throw new CertificateException('Argo CD showed a certificate that is not the one stored in the cluster')
            }
            X509Certificate[] getAcceptedIssuers() { new X509Certificate[0] }
        }
        SSLContext ctx = SSLContext.getInstance('TLS')
        ctx.init(null, [tm] as TrustManager[], new java.security.SecureRandom())
        ctx
    }

    static String imageTag(Map dep) {
        String image = dep?.spec?.template?.spec?.containers?.find { it.name in ['argocd-server', 'argocd'] }?.image ?:
            dep?.spec?.template?.spec?.containers?.getAt(0)?.image ?: ''
        image.contains(':') ? image.substring(image.lastIndexOf(':') + 1) : ''
    }

    private static String decode(Object b64) { new String((b64 as String).decodeBase64(), 'UTF-8') }

    private static String enc(String s) { URLEncoder.encode(s, 'UTF-8') }
}
