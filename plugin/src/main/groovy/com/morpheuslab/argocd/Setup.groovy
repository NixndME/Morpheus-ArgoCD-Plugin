package com.morpheuslab.argocd

import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.ConcurrentHashMap

/** Role permission "Argo CD Admin": install and remove Argo CD, set the connection, see the admin password. */
class AdminAccess {
    static boolean canAdmin(User user) { user?.permissions?.get(ArgoCdPlugin.PERMISSION_ADMIN) == 'full' }

    /** HKS clusters are the Kubernetes clusters Morpheus built itself. */
    static boolean isHks(ComputeServerGroup cluster) { cluster?.type?.code == 'kubernetes-cluster' }
}

/** Spring's current request and session, read without a compile dependency on Spring. */
class Web {
    static Object request() {
        try {
            ClassLoader cl = Thread.currentThread().contextClassLoader
            Class rch = Class.forName('org.springframework.web.context.request.RequestContextHolder', true, cl)
            return rch.getMethod('getRequestAttributes').invoke(null)?.getRequest()
        } catch (Throwable t) {
            return null
        }
    }

    static String session() {
        try { request()?.getSession(false)?.getId() ?: '' } catch (Throwable t) { '' }
    }
}

/** One message after a setup action, and the open sub tab, per login session. */
class Flash {
    private static final Map<String, Map> MESSAGES = new ConcurrentHashMap<>()

    static void set(String level, String text, String sub) {
        String s = Web.session()
        if (s) MESSAGES.put(s, [level: level, text: text, sub: sub, at: System.currentTimeMillis()])
    }

    static Map take() {
        String s = Web.session()
        Map m = s ? MESSAGES.remove(s) : null
        m && System.currentTimeMillis() - (m.at as long) < 600_000L ? m : null
    }
}

/** Lines written by install and remove jobs, kept in memory for the Activity box. */
class Activity {
    private static final List<Map> LINES = Collections.synchronizedList(new ArrayList<Map>())
    private static final Map<Long, String> RUNNING = new ConcurrentHashMap<>()
    static final int KEEP = 300

    static void say(Long clusterId, String cluster, String text) {
        LINES.add([at: System.currentTimeMillis(), clusterId: clusterId, cluster: cluster, text: text])
        synchronized (LINES) { while (LINES.size() > KEEP) LINES.remove(0) }
    }

    static List<Map> lines(int max) {
        List<Map> all
        synchronized (LINES) { all = new ArrayList<Map>(LINES) }
        all.size() > max ? all.subList(all.size() - max, all.size()) : all
    }

    static boolean start(Long clusterId, String what) { RUNNING.putIfAbsent(clusterId, what) == null }

    static void done(Long clusterId) { RUNNING.remove(clusterId) }

    static String running(Long clusterId) { RUNNING.get(clusterId) }

    static boolean getAnyRunning() { !RUNNING.isEmpty() }

    static void clear() { LINES.clear(); RUNNING.clear() }
}

/**
 * This Morpheus' own REST API, called as the signed-in user (session cookie + CSRF header), so Morpheus' own
 * rules apply. Captured during a request, so a background job can still use it when it finishes.
 */
@Slf4j
class SelfApi {
    String base
    String cookie
    String csrfHeader
    String csrfValue

    /** Morpheus on this machine; the session cookie never goes to a host taken from the request. */
    static final String LOCAL = 'https://127.0.0.1'

    static SelfApi capture() {
        Object req = Web.request()
        if (!req) return null
        Object tok = req.getAttribute('_csrf') ?: req.getAttribute('org.springframework.security.web.csrf.CsrfToken')
        new SelfApi(base: LOCAL, cookie: req.getHeader('Cookie'), csrfHeader: tok?.headerName as String, csrfValue: tok?.token as String)
    }

    /** The signed-in user's access to one plugin permission, from /api/whoami; null when Morpheus did not answer. */
    String permission(String code) {
        Map w = call('GET', '/api/whoami', null)
        if (w._status != 200) return null
        Object perms = w.permissions
        Map p = perms instanceof List ? ((List<Map>) perms).find { it.code == code } : null
        (p?.access ?: 'none') as String
    }

    /** Ids of the clusters Morpheus lets this user see (tenant, groups, role). */
    Set<Long> visibleClusterIds() {
        Map r = call('GET', '/api/clusters?max=1000', null)
        ((r.clusters ?: []) as List<Map>).collect { it.id as Long } as Set<Long>
    }

    boolean seesCluster(Long id) { ((call('GET', "/api/clusters/${id}", null).cluster?.id) as Long) == id }

    /** Morpheus replaces all plugin settings on save, so every field is sent. Returns null or an error. */
    String saveSettings(Map config) {
        Map list = call('GET', '/api/plugins?max=200', null)
        Map me = ((list.plugins ?: []) as List<Map>).find { it.code == 'argocd' }
        if (!me) return "Could not find this plugin in Morpheus (${list._error ?: 'HTTP ' + list._status})."
        Map r = call('PUT', "/api/plugins/${me.id}", [plugin: [config: config]])
        r._status == 200 && r.success != false ? null : "Morpheus did not save the settings (${r._error ?: r.msg ?: 'HTTP ' + r._status})."
    }

    Map call(String method, String path, Map body) {
        HttpURLConnection c = null
        try {
            c = (HttpURLConnection) new URL(base + path).openConnection()
            if (c instanceof HttpsURLConnection) {
                // Morpheus often has a self-signed certificate and we only call ourselves
                ((HttpsURLConnection) c).SSLSocketFactory = Kube.trustAll()
                ((HttpsURLConnection) c).hostnameVerifier = { h, s -> true }
            }
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            c.setRequestProperty('Accept', 'application/json')
            c.setRequestProperty('Content-Type', 'application/json')
            if (cookie) c.setRequestProperty('Cookie', cookie)
            if (csrfHeader && csrfValue) c.setRequestProperty(csrfHeader, csrfValue)
            if (body != null) {
                c.doOutput = true
                c.outputStream.withWriter('UTF-8') { it << JsonOutput.toJson(body) }
            }
            int code = c.responseCode
            String text = (code < 400 ? c.inputStream : c.errorStream)?.getText('UTF-8') ?: ''
            Object parsed = null
            try { parsed = text ? new JsonSlurper().parseText(text) : [:] } catch (Exception ignored) { }
            Map out = parsed instanceof Map ? new LinkedHashMap((Map) parsed) : [:]
            out._status = code
            if (code >= 300) out._error = "HTTP ${code}"
            out
        } catch (Throwable t) {
            log.warn("ArgoCD: ${method} ${path} on Morpheus failed: ${t}")
            [_status: 0, _error: "${t.class.simpleName}: ${t.message}"]
        } finally {
            c?.disconnect()
        }
    }
}
