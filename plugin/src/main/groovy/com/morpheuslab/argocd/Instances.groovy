package com.morpheuslab.argocd

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.cypher.CypherAccess
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.AccountIntegration
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/** One Argo CD the plugin talks to: an Argo CD integration and its connection (kept in Cypher). */
class ArgoInstance {
    Long id
    String label
    Long accountId
    boolean master
    String url
    String token
    boolean verifyTls
    String project

    ArgoCdSettings settings() { new ArgoCdSettings(url: url, token: token, verifyTls: verifyTls, defaultProject: project ?: 'default') }

    /** Argo CDs of the main tenant are for every tenant; a tenant's own Argo CD only for that tenant. */
    boolean visibleTo(Long userAccountId) { master || (userAccountId != null && userAccountId == accountId) }
}

/** Every Argo CD connection: one per Argo CD integration, plus the old plugin settings until they are moved. */
@Slf4j
class Instances {

    static final String TYPE = 'argocd-integration'
    static final String PATH = 'secret/argocd/'
    static final Long LEGACY_ID = 0L
    static final String LEGACY_LABEL = 'Argo CD (plugin settings)'
    static final long CACHE_MILLIS = 15_000L

    private static volatile Map cache

    // ---------- pure helpers (unit tested) ----------

    static String toJson(Map conn) {
        JsonOutput.toJson([url: conn.url, token: conn.token, verifyTls: conn.verifyTls as boolean, project: conn.project ?: 'default'])
    }

    /** The stored connection, or null when there is none or it is unusable. */
    static Map parse(String json) {
        if (!json?.trim()) return null
        try {
            Object d = new JsonSlurper().parseText(json)
            (d instanceof Map && d.url && d.token) ? [url: d.url, token: d.token, verifyTls: d.verifyTls as boolean, project: d.project ?: 'default'] : null
        } catch (Exception ignored) {
            null
        }
    }

    static ArgoInstance instance(Long id, String label, Long accountId, boolean master, Map conn) {
        new ArgoInstance(id: id, label: label, accountId: accountId, master: master,
            url: (conn.url as String)?.trim()?.replaceAll('/+$', ''), token: conn.token as String,
            verifyTls: conn.verifyTls as boolean, project: (conn.project ?: 'default') as String)
    }

    /** Add the old plugin settings as one more Argo CD, unless an integration already has that address. */
    static List<ArgoInstance> withLegacy(List<ArgoInstance> list, ArgoCdSettings old) {
        if (!old?.configured || list.any { it.url == old.url }) return list
        list + [instance(LEGACY_ID, LEGACY_LABEL, null, true, [url: old.url, token: old.token, verifyTls: old.verifyTls, project: old.defaultProject])]
    }

    // ---------- live ----------

    /** All Argo CDs with a connection (cached a few seconds; every tab and action asks). */
    static List<ArgoInstance> all(MorpheusContext morpheus, Plugin plugin) {
        Map c = cache
        if (c && System.currentTimeMillis() - (c.at as long) < CACHE_MILLIS) return c.list as List<ArgoInstance>
        List<ArgoInstance> list = []
        integrations(morpheus).each { AccountIntegration i ->
            Map conn = parse(read(morpheus, i))
            if (conn) list << instance(i.id, i.name, i.account?.id, i.account?.masterAccount as boolean, conn)
        }
        list = withLegacy(list.sort { it.label?.toLowerCase() }, ArgoCdSettings.read(morpheus, plugin))
        cache = [at: System.currentTimeMillis(), list: list]
        list
    }

    static List<ArgoInstance> visibleTo(MorpheusContext morpheus, Plugin plugin, Long accountId) {
        all(morpheus, plugin).findAll { it.visibleTo(accountId) }
    }

    /** The Argo CD by id; null id means the only one there is (forms from before several Argo CDs). */
    static ArgoInstance find(MorpheusContext morpheus, Plugin plugin, Long accountId, Long id) {
        List<ArgoInstance> mine = visibleTo(morpheus, plugin, accountId)
        id != null ? mine.find { it.id == id } : (mine.size() == 1 ? mine[0] : null)
    }

    static List<AccountIntegration> integrations(MorpheusContext morpheus) {
        try {
            morpheus.services.accountIntegration.list(new DataQuery().withFilter('type', TYPE)) as List<AccountIntegration>
        } catch (Throwable t) {
            log.warn("ArgoCD: could not list the Argo CD integrations: ${t}")
            []
        }
    }

    static AccountIntegration integration(MorpheusContext morpheus, Long id) {
        integrations(morpheus).find { it.id == id }
    }

    static Map connection(MorpheusContext morpheus, AccountIntegration i) { parse(read(morpheus, i)) }

    /** Save the connection of one integration. Returns null or an error. */
    static String save(MorpheusContext morpheus, AccountIntegration i, Map conn) {
        try {
            CypherAccess access = new CypherAccess(i.account)
            morpheus.cypher.write(access, key(i), toJson(conn), 0L).blockingGet()
            cache = null
            null
        } catch (Throwable t) {
            log.error("ArgoCD: could not save the connection of integration ${i?.id}", t)
            "Could not save the connection in Morpheus Cypher: ${t.message}".toString()
        }
    }

    static String clear(MorpheusContext morpheus, AccountIntegration i) {
        try {
            morpheus.cypher.delete(new CypherAccess(i.account), key(i)).blockingGet()
            cache = null
            null
        } catch (Throwable t) {
            log.error("ArgoCD: could not clear the connection of integration ${i?.id}", t)
            "Could not clear the connection in Morpheus Cypher: ${t.message}".toString()
        }
    }

    static void forget() { cache = null }

    private static String read(MorpheusContext morpheus, AccountIntegration i) {
        try {
            morpheus.cypher.read(new CypherAccess(i.account), key(i)).blockingGet()
        } catch (Throwable t) {
            log.warn("ArgoCD: could not read the connection of integration ${i?.id}: ${t}")
            null
        }
    }

    private static String key(AccountIntegration i) { PATH + i.id }

    /** The tenant of a user (the User handed to plugins has no account). */
    static Account accountOf(MorpheusContext morpheus, def user) {
        if (user?.account) return user.account as Account
        try { morpheus.services.admin.user.get(user.id as Long)?.account } catch (Throwable ignored) { null }
    }
}
