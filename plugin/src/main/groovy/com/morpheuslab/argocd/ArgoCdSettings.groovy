package com.morpheuslab.argocd

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

@Slf4j
class ArgoCdSettings {

    static final String URL_FIELD = 'argocdUrl'
    static final String TOKEN_FIELD = 'argocdToken'
    static final String VERIFY_TLS_FIELD = 'argocdVerifyTls'
    static final String PROJECT_FIELD = 'argocdDefaultProject'

    String url
    String token
    boolean verifyTls = true
    String defaultProject

    boolean isConfigured() { url && token }

    static ArgoCdSettings read(MorpheusContext morpheus, Plugin plugin) {
        try {
            return parse(morpheus.getSettings(plugin).blockingGet())
        } catch (Throwable t) {
            log.error('ArgoCD: could not read plugin settings', t)
            return new ArgoCdSettings()
        }
    }

    static ArgoCdSettings parse(String json) {
        ArgoCdSettings s = new ArgoCdSettings()
        if (!json?.trim()) return s
        Object doc
        try { doc = new JsonSlurper().parseText(json) } catch (Exception ignored) { return s }
        if (!(doc instanceof Map)) return s
        s.url = value(doc[URL_FIELD])?.replaceAll('/+$', '')
        s.token = value(doc[TOKEN_FIELD])
        s.defaultProject = value(doc[PROJECT_FIELD])
        Object v = doc[VERIFY_TLS_FIELD]
        s.verifyTls = v == null || !(v.toString().trim().toLowerCase() in ['off', 'false', '0', 'no'])
        return s
    }

    // Morpheus stores a cleared field as "" rather than dropping the key
    private static String value(Object raw) { raw?.toString()?.trim() ?: null }
}
