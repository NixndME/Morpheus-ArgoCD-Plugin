package com.morpheuslab.argocd

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServerGroup
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/** Kubernetes API calls with the token Morpheus keeps for the cluster. */
@Slf4j
class Kube {

    String apiUrl
    String token
    Map<String, String> plurals = [:]

    /** (method, url, body, contentType, token) -> [status, body]. Tests swap it. */
    Closure<Map> transport = Kube.&send

    static Kube of(MorpheusContext morpheus, ComputeServerGroup cluster) {
        String t = cluster.serviceToken
        if (!t && cluster.id) {
            try { t = morpheus.services.cluster.get(cluster.id)?.serviceToken } catch (Throwable ignored) { }
        }
        new Kube(apiUrl: cluster.serviceUrl?.replaceAll('/+$', ''), token: t)
    }

    boolean isUsable() { apiUrl && token }

    Map get(String path) { call('GET', path, null) }

    Map post(String path, Map body) { call('POST', path, body) }

    Map delete(String path) { call('DELETE', path, [kind: 'DeleteOptions', apiVersion: 'v1', propagationPolicy: 'Background']) }

    String text(String path) {
        Map r = raw('GET', path, null, null)
        r.status == 200 ? r.body as String : null
    }

    Map put(String path, Map body) { call('PUT', path, body) }

    /** Create the object, or replace it when it is already there. */
    Map apply(Map obj) {
        String path = objectPath(obj)
        if (!path) return [status: 0, error: "Unknown kind ${obj.apiVersion} ${obj.kind}"]
        Map r = post(path.substring(0, path.lastIndexOf('/')), obj)
        if (r.status != 409) return r
        Map cur = get(path)
        if (cur.status != 200) return cur
        Map copy = new LinkedHashMap(obj)
        copy.metadata = new LinkedHashMap(obj.metadata as Map) + [resourceVersion: cur.data.metadata.resourceVersion]
        put(path, copy)
    }

    Map remove(Map obj) {
        String path = objectPath(obj)
        path ? delete(path) : [status: 0, error: "Unknown kind ${obj.kind}"]
    }

    /** API path of one object, for example /apis/apps/v1/namespaces/velero/deployments/velero. */
    String objectPath(Map obj) {
        String base = obj.apiVersion == 'v1' ? '/api/v1' : "/apis/${obj.apiVersion}"
        String plural = plural(obj.apiVersion as String, obj.kind as String)
        if (!plural) return null
        String ns = obj.metadata?.namespace
        "${base}${ns ? "/namespaces/${ns}" : ''}/${plural}/${obj.metadata.name}"
    }

    String plural(String apiVersion, String kind) {
        String key = "${apiVersion}/${kind}"
        if (!plurals.containsKey(key)) {
            String base = apiVersion == 'v1' ? '/api/v1' : "/apis/${apiVersion}"
            Map r = get(base)
            (r.data?.resources ?: []).each { Map res ->
                if (!(res.name as String).contains('/')) plurals["${apiVersion}/${res.kind}".toString()] = res.name as String
            }
        }
        plurals[key]
    }

    Map call(String method, String path, Map body) {
        result(raw(method, path, body == null ? null : JsonOutput.toJson(body), 'application/json'))
    }

    private static Map result(Map r) {
        Object data = null
        if (r.body) { try { data = new JsonSlurper().parseText(r.body as String) } catch (Exception ignored) { } }
        int status = (r.status ?: 0) as int
        String err = null
        if (status >= 400 || status == 0) err = (data instanceof Map ? data.message : null) ?: r.error ?: "HTTP ${status}"
        [status: status, data: data, error: err]
    }

    private Map raw(String method, String path, String body, String contentType) {
        if (!usable) return [status: 0, error: 'Morpheus has no API access stored for this cluster']
        try {
            transport.call(method, apiUrl + path, body, contentType, token)
        } catch (Throwable t) {
            log.debug("ArgoCD: ${method} ${path} failed: ${t}")
            [status: 0, error: "${t.class.simpleName}: ${t.message}"]
        }
    }

    static final int READ_MILLIS = 60_000

    static Map send(String method, String url, String body, String contentType, String bearer, int readMillis = READ_MILLIS) {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection()
        try {
            if (c instanceof HttpsURLConnection) {
                // same trust Morpheus uses for the cluster API
                ((HttpsURLConnection) c).SSLSocketFactory = trustAll()
                ((HttpsURLConnection) c).hostnameVerifier = { String h, SSLSession s -> true }
            }
            c.requestMethod = method
            c.connectTimeout = 3_000
            c.instanceFollowRedirects = false
            c.readTimeout = readMillis
            c.setRequestProperty('Accept', 'application/json, */*')
            if (bearer) c.setRequestProperty('Authorization', "Bearer ${bearer}")
            if (body != null) {
                c.setRequestProperty('Content-Type', contentType)
                c.doOutput = true
                c.outputStream.withStream { it.write(body.getBytes('UTF-8')) }
            }
            int status = c.responseCode
            InputStream stream = status >= 400 ? c.errorStream : c.inputStream
            [status: status, body: stream?.getText('UTF-8') ?: '']
        } finally {
            c.disconnect()
        }
    }

    static javax.net.ssl.SSLSocketFactory trustAll() {
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
