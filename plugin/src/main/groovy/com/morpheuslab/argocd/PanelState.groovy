package com.morpheuslab.argocd

import com.morpheusdata.model.User
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/**
 * Panel state per login session and cluster: an action records its result (and the selection) here,
 * redirects back to the tab, and the tab renders from it. Two browsers of the same user stay separate.
 */
class PanelState {

    static class State {
        String selectedApp
        String selectedResource        // ResourceTree key or uid
        Map selectedRef                // kind/name/namespace/group/version/uid of the selected resource
        String flashLevel              // success | danger | info
        String flashMessage
        long at = System.currentTimeMillis()
    }

    private static final Map<String, State> STATES = new ConcurrentHashMap<>()
    private static final long TTL_MILLIS = 8L * 3600 * 1000

    static String key(Long userId, Long clusterId) { "${userId ?: 0}:${clusterId ?: 0}:${sessionId()}".toString() }

    /** The HTTP session of the current request (Spring's RequestContextHolder, looked up without a compile dependency). */
    static String sessionId() {
        try {
            ClassLoader cl = Thread.currentThread().contextClassLoader
            Class rch = Class.forName('org.springframework.web.context.request.RequestContextHolder', true, cl)
            return rch.getMethod('getRequestAttributes').invoke(null)?.getSessionId() ?: ''
        } catch (Throwable t) {
            return ''
        }
    }

    static State get(Long userId, Long clusterId) {
        State s = STATES.get(key(userId, clusterId))
        if (s && System.currentTimeMillis() - s.at > TTL_MILLIS) { STATES.remove(key(userId, clusterId)); return null }
        s
    }

    static State update(Long userId, Long clusterId, Closure change) {
        State s = STATES.computeIfAbsent(key(userId, clusterId)) { new State() }
        change.call(s)
        s.at = System.currentTimeMillis()
        s
    }

    /** The banner shows once. */
    static Map takeFlash(Long userId, Long clusterId) {
        State s = get(userId, clusterId)
        if (!s?.flashMessage) return null
        Map f = [level: s.flashLevel ?: 'info', message: s.flashMessage]
        s.flashMessage = null
        f
    }
}

/** Morpheus RBAC for the plugin's own permission (Roles > Argo CD Applications: None / Read / Full). */
class Access {
    static String level(User user) {
        String v = user?.permissions?.get(ArgoCdPlugin.PERMISSION_APPS)
        v ?: 'none'
    }

    static boolean canRead(User user) { level(user) in ['read', 'full'] }

    static boolean canManage(User user) { level(user) == 'full' }
}

/**
 * Server-side CSRF token for plain HTML forms (the panel uses no JavaScript). Spring Security exposes the
 * token as a request attribute; the request is reached through Spring's RequestContextHolder, looked up
 * reflectively so the plugin needs no Spring compile dependency.
 */
@Slf4j
class Csrf {
    static Map token() {
        try {
            ClassLoader cl = Thread.currentThread().contextClassLoader
            Class rch = Class.forName('org.springframework.web.context.request.RequestContextHolder', true, cl)
            Object attrs = rch.getMethod('getRequestAttributes').invoke(null)
            Object req = attrs?.respondsTo('getRequest') ? attrs.getRequest() : null
            Object tok = req?.getAttribute('_csrf') ?: req?.getAttribute('org.springframework.security.web.csrf.CsrfToken')
            if (tok) return [param: tok.parameterName as String, value: tok.token as String]
        } catch (Throwable t) {
            log.warn("ArgoCD: no CSRF token available: ${t}")
        }
        [param: '_csrf', value: '']
    }

    static Map token(Object request) {
        Object tok = request?.getAttribute('_csrf') ?: request?.getAttribute('org.springframework.security.web.csrf.CsrfToken')
        tok ? [param: tok.parameterName as String, value: tok.token as String] : token()
    }
}
