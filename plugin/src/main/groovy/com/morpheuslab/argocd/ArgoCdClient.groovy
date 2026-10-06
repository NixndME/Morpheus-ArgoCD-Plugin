package com.morpheuslab.argocd

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Argo CD REST API client (Bearer token). Every call returns an ArgoResult — an API failure is a
 * value the UI explains (unreachable / token rejected / forbidden / not found), never a throw.
 */
@Slf4j
class ArgoCdClient {

    static final int CONNECT_TIMEOUT_MILLIS = 5_000
    static final int READ_TIMEOUT_MILLIS = 20_000

    String baseUrl
    String token
    boolean verifyTls = true

    /** (method, url, jsonBody, token, verifyTls) -> [status: int, body: String]; swappable in tests. */
    Closure<Map> transport = ArgoCdClient.&httpSend

    static ArgoCdClient of(ArgoCdSettings s) {
        new ArgoCdClient(baseUrl: s.url, token: s.token, verifyTls: s.verifyTls)
    }

    ArgoResult version() { call('GET', '/api/version', null, false) }

    ArgoResult userInfo() { call('GET', '/api/v1/session/userinfo') }

    ArgoResult listApplications() { call('GET', '/api/v1/applications') }

    ArgoResult getApplication(String name, String refresh = null) {
        call('GET', "/api/v1/applications/${enc(name)}" + (refresh ? "?refresh=${enc(refresh)}" : ''))
    }

    ArgoResult createApplication(Map application) {
        call('POST', '/api/v1/applications', application)
    }

    /**
     * Sync with options: revision, dryRun, prune, force, and a subset of resources (empty = all).
     * requestedBy shows in Argo CD's sync details, so Argo CD knows which Morpheus user asked.
     */
    ArgoResult syncApplication(String name, Map opts, String requestedBy) {
        Map body = [prune: opts.prune == true, dryRun: opts.dryRun == true]
        if (opts.revision) body.revision = opts.revision
        if (opts.force) body.strategy = [apply: [force: true]]
        if (opts.resources) body.resources = opts.resources
        if (requestedBy) body.infos = [[name: 'Requested from Morpheus by', value: requestedBy]]
        call('POST', "/api/v1/applications/${enc(name)}/sync", body)
    }

    /** Git (target/predicted) and live state of every resource, for the diff view. */
    ArgoResult managedResources(String name) { call('GET', "/api/v1/applications/${enc(name)}/managed-resources") }

    /** Read the app, let change() edit its spec, write it back (HttpURLConnection cannot send PATCH). */
    ArgoResult updateApplication(String name, Closure change) {
        ArgoResult cur = getApplication(name)
        if (!cur.ok) return cur
        Map app = cur.data as Map
        Map md = new LinkedHashMap(app.metadata as Map)
        md.remove('managedFields')
        Map body = [metadata: md, spec: app.spec]
        change.call(body.spec as Map)
        call('PUT', "/api/v1/applications/${enc(name)}", body)
    }

    ArgoResult rollback(String name, long historyId) {
        call('POST', "/api/v1/applications/${enc(name)}/rollback", [id: historyId, prune: false, dryRun: false])
    }

    /** Actions Argo CD offers for one resource, e.g. restart, pause, resume, scale. */
    ArgoResult resourceActions(String app, Map ref) {
        call('GET', "/api/v1/applications/${enc(app)}/resource/actions?" + refQuery(ref))
    }

    /** Run an action, with parameters when it takes them (scale -> replicas). */
    ArgoResult runResourceAction(String app, Map ref, String action, Map<String, String> params) {
        call('POST', "/api/v1/applications/${enc(app)}/resource/actions/v2", [name: app, namespace: ref.namespace ?: '',
            resourceName: ref.name, group: ref.group ?: '', kind: ref.kind, version: ref.version ?: '', action: action,
            resourceActionParameters: params.collect { k, v -> [name: k, value: v] }])
    }

    /** requestedBy shows in Argo CD's sync details, so Argo CD knows which Morpheus user asked. */
    ArgoResult syncApplication(String name, boolean prune, String requestedBy = null) {
        Map body = [prune: prune, dryRun: false]
        if (requestedBy) body.infos = [[name: 'Requested from Morpheus by', value: requestedBy]]
        call('POST', "/api/v1/applications/${enc(name)}/sync", body)
    }

    ArgoResult deleteApplication(String name, boolean cascade) {
        call('DELETE', "/api/v1/applications/${enc(name)}?cascade=${cascade}")
    }

    ArgoResult resourceTree(String name) { call('GET', "/api/v1/applications/${enc(name)}/resource-tree") }

    /** Live manifest of one managed resource (what Argo CD shows under LIVE MANIFEST). */
    ArgoResult resource(String app, Map ref) {
        call('GET', "/api/v1/applications/${enc(app)}/resource?" + query([
            resourceName: ref.name, namespace: ref.namespace, kind: ref.kind, group: ref.group ?: '', version: ref.version ?: '']))
    }

    /** Kubernetes events for the app, or one of its resources when ref is given. */
    ArgoResult events(String app, Map ref = null) {
        String q = ref ? '?' + query([resourceName: ref.name, resourceNamespace: ref.namespace, resourceUID: ref.uid ?: '']) : ''
        call('GET', "/api/v1/applications/${enc(app)}/events${q}")
    }

    /** Run a built-in Argo CD resource action, e.g. "restart" on a Deployment. */
    ArgoResult runResourceAction(String app, Map ref, String action) {
        call('POST', "/api/v1/applications/${enc(app)}/resource/actions?" + refQuery(ref), action)
    }

    ArgoResult deleteResource(String app, Map ref, boolean force) {
        call('DELETE', "/api/v1/applications/${enc(app)}/resource?" + refQuery(ref) + "&force=${force}&orphan=false")
    }

    /** Sync just one resource of the app (Argo CD's per-resource SYNC). */
    ArgoResult syncResource(String app, Map ref) {
        call('POST', "/api/v1/applications/${enc(app)}/sync", [prune: false, dryRun: false,
            resources: [[group: ref.group ?: '', kind: ref.kind, name: ref.name, namespace: ref.namespace ?: '']]])
    }

    /** Last lines of a pod's log. Argo CD streams newline-delimited JSON {"result":{"content":...}}. */
    ArgoResult podLogs(String app, Map ref, int tail) {
        ArgoResult r = call('GET', "/api/v1/applications/${enc(app)}/logs?" + query([podName: ref.name, namespace: ref.namespace,
            tailLines: tail as String, follow: 'false']))
        if (r.ok) {
            // NDJSON: JsonSlurper would accept the first object and silently drop the rest — parse per line
            String raw = r.raw ?: ''
            List<String> lines = []
            raw.readLines().each { String l ->
                try {
                    Object o = new JsonSlurper().parseText(l)
                    Object c = o?.result?.content
                    if (c != null && !(o.result.last == true && !c)) lines << (c as String)
                } catch (Exception ignored) { if (l.trim()) lines << l }
            }
            return new ArgoResult(kind: ArgoResult.Kind.OK, status: r.status, data: lines)
        }
        r
    }

    private static String refQuery(Map ref) {
        query([namespace: ref.namespace ?: '', resourceName: ref.name, version: ref.version ?: '', group: ref.group ?: '', kind: ref.kind])
    }

    ArgoResult listClusters() { call('GET', '/api/v1/clusters') }

    ArgoResult listProjects() { call('GET', '/api/v1/projects') }

    ArgoResult call(String method, String path, Object body = null, boolean auth = true) {
        String url = baseUrl + path
        Map res
        try {
            res = transport.call(method, url, body == null ? null : JsonOutput.toJson(body), auth ? token : null, verifyTls)
        } catch (Throwable t) {
            log.warn("ArgoCD: ${method} ${path} failed: ${t}")
            return ArgoResult.unreachable("${t.class.simpleName}: ${t.message}")
        }
        return ArgoResult.from((res.status ?: 0) as int, res.body as String)
    }

    private static String query(Map m) { m.findAll { it.value != null }.collect { k, v -> "${k}=${enc(v as String)}" }.join('&') }

    private static String enc(String s) { URLEncoder.encode(s ?: '', 'UTF-8') }

    static Map httpSend(String method, String url, String json, String bearer, boolean verifyTls) {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection()
        try {
            if (!verifyTls && c instanceof HttpsURLConnection) {
                ((HttpsURLConnection) c).SSLSocketFactory = trustAll()
                ((HttpsURLConnection) c).hostnameVerifier = { String h, SSLSession s -> true }
            }
            c.requestMethod = method
            c.connectTimeout = CONNECT_TIMEOUT_MILLIS
            c.readTimeout = READ_TIMEOUT_MILLIS
            c.setRequestProperty('Accept', 'application/json')
            if (bearer) c.setRequestProperty('Authorization', "Bearer ${bearer}")
            // Argo CD's gateway answers 415 to a body-less DELETE/POST without a JSON content type
            if (method != 'GET') c.setRequestProperty('Content-Type', 'application/json')
            if (json != null) {
                c.doOutput = true
                c.outputStream.withStream { it.write(json.getBytes('UTF-8')) }
            }
            int status = c.responseCode
            InputStream stream = status >= 400 ? (c.errorStream ?: null) : c.inputStream
            return [status: status, body: stream?.getText('UTF-8') ?: '']
        } finally {
            c.disconnect()
        }
    }

    private static javax.net.ssl.SSLSocketFactory trustAll() {
        TrustManager[] tm = [new X509TrustManager() {
            void checkClientTrusted(X509Certificate[] chain, String authType) {}
            void checkServerTrusted(X509Certificate[] chain, String authType) {}
            X509Certificate[] getAcceptedIssuers() { new X509Certificate[0] }
        }] as TrustManager[]
        SSLContext ctx = SSLContext.getInstance('TLS')
        ctx.init(null, tm, new java.security.SecureRandom())
        ctx.socketFactory
    }
}

class ArgoResult {
    enum Kind { OK, UNREACHABLE, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, ERROR }

    Kind kind
    int status
    Object data
    String message
    String raw

    boolean isOk() { kind == Kind.OK }

    static ArgoResult unreachable(String why) { new ArgoResult(kind: Kind.UNREACHABLE, message: why) }

    static ArgoResult from(int status, String body) {
        Object data = null
        if (body?.trim()) {
            try { data = new JsonSlurper().parseText(body) } catch (Exception ignored) { data = body }
        }
        String msg = (data instanceof Map) ? (data.message ?: data.error) : (data instanceof String ? data.take(300) : null)
        Kind k
        if (status >= 200 && status < 300) k = Kind.OK
        else if (status == 401) k = Kind.UNAUTHORIZED
        else if (status == 403) k = Kind.FORBIDDEN
        else if (status == 404) k = Kind.NOT_FOUND
        else k = Kind.ERROR
        new ArgoResult(kind: k, status: status, data: data, message: msg, raw: body)
    }

    /** One line a human can act on. */
    String describe() {
        switch (kind) {
            case Kind.OK: return 'OK'
            case Kind.UNREACHABLE: return "Argo CD is unreachable from the Morpheus appliance (${message})"
            case Kind.UNAUTHORIZED: return 'Argo CD rejected the API token — it may be expired or revoked. Update it in the plugin settings.'
            case Kind.FORBIDDEN: return "The Argo CD account behind this token is not allowed to do that (Argo CD RBAC): ${message ?: 'permission denied'}"
            case Kind.NOT_FOUND: return "Not found in Argo CD: ${message ?: ''}".trim()
            default: return "Argo CD returned HTTP ${status}: ${message ?: 'no details'}"
        }
    }
}
