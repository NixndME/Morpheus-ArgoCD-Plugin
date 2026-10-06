package com.morpheuslab.argocd

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/** Read-only checks against a cluster API, used only to prove which cluster it is. */
@Slf4j
class K8sProbe {

    /** kind -> [api prefix, plural, namespaced]. Covers what Argo CD apps commonly manage. */
    static final Map<String, List> KINDS = [
        'Service'       : ['/api/v1', 'services', true],
        'ConfigMap'     : ['/api/v1', 'configmaps', true],
        'ServiceAccount': ['/api/v1', 'serviceaccounts', true],
        'Pod'           : ['/api/v1', 'pods', true],
        'Namespace'     : ['/api/v1', 'namespaces', false],
        'Deployment'    : ['/apis/apps/v1', 'deployments', true],
        'StatefulSet'   : ['/apis/apps/v1', 'statefulsets', true],
        'DaemonSet'     : ['/apis/apps/v1', 'daemonsets', true],
        'ReplicaSet'    : ['/apis/apps/v1', 'replicasets', true],
        'Job'           : ['/apis/batch/v1', 'jobs', true],
        'CronJob'       : ['/apis/batch/v1', 'cronjobs', true],
        'Ingress'       : ['/apis/networking.k8s.io/v1', 'ingresses', true]
    ]

    /** Most stable first: a Service/Deployment outlives any Pod. */
    static final List<String> PREFERRED = ['Service', 'Deployment', 'StatefulSet', 'DaemonSet', 'ConfigMap',
                                           'ServiceAccount', 'Ingress', 'CronJob', 'Job', 'ReplicaSet', 'Pod', 'Namespace']

    String apiUrl
    String token

    /** (method, url, jsonBody, token, verifyTls) -> [status, body]; same contract as ArgoCdClient, swappable in tests. */
    Closure<Map> transport = ArgoCdClient.&httpSend
    /** url -> hex Authority Key Identifier of the presented certificate (or null); swappable in tests. */
    Closure<String> akiReader = K8sProbe.&authorityKeyId

    /** Set when the cluster API cannot be reached; later checks return UNKNOWN at once instead of timing out again. */
    boolean down

    boolean isUsable() { apiUrl && token && !down }

    static String path(String kind, String namespace, String name) {
        List k = KINDS[kind]
        if (!k || !name) return null
        String ns = (k[2] && namespace) ? "/namespaces/${enc(namespace)}" : ''
        if (k[2] && !namespace) return null
        "${k[0]}${ns}/${k[1]}/${enc(name)}"
    }

    /** MATCH = same uid; MISMATCH = missing or other uid (another cluster); UNKNOWN = could not check. */
    enum Proof { MATCH, MISMATCH, UNKNOWN }

    Proof objectUid(String kind, String namespace, String name, String uid) {
        String p = path(kind, namespace, name)
        if (!usable || !p || !uid) return Proof.UNKNOWN
        Map res
        try {
            res = transport.call('GET', apiUrl.replaceAll('/+$', '') + p, null, token, false)
        } catch (Throwable t) {
            log.debug("ArgoCD: k8s probe ${p} failed: ${t}")
            down = true
            return Proof.UNKNOWN
        }
        int status = (res.status ?: 0) as int
        if (status == 404) return Proof.MISMATCH
        if (status != 200) return Proof.UNKNOWN
        try {
            Object doc = new JsonSlurper().parseText(res.body as String)
            return doc?.metadata?.uid == uid ? Proof.MATCH : Proof.MISMATCH
        } catch (Exception ignored) {
            return Proof.UNKNOWN
        }
    }

    String caKeyId(String url) {
        try { akiReader.call(url) } catch (Throwable t) { log.debug("ArgoCD: TLS probe ${url} failed: ${t}"); null }
    }

    /** The API server certificate names its cluster CA key (Authority Key Identifier), unique per cluster. */
    static String authorityKeyId(String url) {
        URI u = new URI(url.trim())
        if (u.scheme != 'https' || !u.host) return null
        TrustManager[] tm = [new X509TrustManager() {
            void checkClientTrusted(X509Certificate[] chain, String authType) {}
            void checkServerTrusted(X509Certificate[] chain, String authType) {}
            X509Certificate[] getAcceptedIssuers() { new X509Certificate[0] }
        }] as TrustManager[]
        SSLContext ctx = SSLContext.getInstance('TLS')
        ctx.init(null, tm, new java.security.SecureRandom())
        SSLSocket s = (SSLSocket) ctx.socketFactory.createSocket()
        try {
            s.connect(new InetSocketAddress(u.host, u.port > 0 ? u.port : 443), ArgoCdClient.CONNECT_TIMEOUT_MILLIS)
            s.soTimeout = ArgoCdClient.CONNECT_TIMEOUT_MILLIS
            s.startHandshake()
            X509Certificate leaf = (X509Certificate) s.session.peerCertificates[0]
            return akiHex(leaf.getExtensionValue('2.5.29.35'))
        } finally {
            s.close()
        }
    }

    /** DER: OCTET STRING { SEQUENCE { [0] keyIdentifier, ... } } -> hex of keyIdentifier. */
    static String akiHex(byte[] ext) {
        if (!ext) return null
        int[] pos = [0] as int[]
        if (ext[pos[0]++] != 0x04) return null
        readLen(ext, pos)
        if (ext[pos[0]++] != 0x30) return null
        int seqLen = readLen(ext, pos)
        int end = pos[0] + seqLen
        while (pos[0] < end) {
            int tag = ext[pos[0]++] & 0xff
            int len = readLen(ext, pos)
            if (tag == 0x80) return ext[pos[0]..<(pos[0] + len)].collect { String.format('%02X', it & 0xff) }.join(':')
            pos[0] += len
        }
        null
    }

    private static int readLen(byte[] b, int[] pos) {
        int first = b[pos[0]++] & 0xff
        if (first < 0x80) return first
        int n = first & 0x7f, len = 0
        n.times { len = (len << 8) | (b[pos[0]++] & 0xff) }
        len
    }

    private static String enc(String s) { URLEncoder.encode(s, 'UTF-8') }
}
