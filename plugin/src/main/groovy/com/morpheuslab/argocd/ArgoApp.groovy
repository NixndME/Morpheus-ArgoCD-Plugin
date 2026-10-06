package com.morpheuslab.argocd

import java.time.Duration
import java.time.Instant

/** One Argo CD Application, flattened to what the UI shows. Every display value is precomputed. */
class ArgoApp {
    String name
    String project
    String repoUrl
    String path
    String chart
    String targetRevision
    String destServer
    String destName
    String namespace
    String syncStatus      // Synced | OutOfSync | Unknown
    String healthStatus    // Healthy | Progressing | Degraded | Suspended | Missing | Unknown
    String revision
    String operationPhase  // Running | Succeeded | Failed | Error | Terminating
    String operationMessage
    String reconciledAt
    String lastSyncAt
    boolean autoSync
    boolean autoPrune
    boolean selfHeal
    boolean deleting
    boolean multiSource
    String sourceType      // Directory | Helm | Kustomize | Plugin
    List<String> helmValueFiles = []
    List<Map> helmParams = []
    List<String> kustomizeImages = []
    Integer viewIndex
    List<ArgoResource> resources = []
    List<ArgoHistory> history = []
    List<String> conditions = []

    static ArgoApp parse(Map item) {
        Map spec = (item.spec ?: [:]) as Map
        Map src = (spec.source ?: ((spec.sources instanceof List && spec.sources) ? spec.sources[0] : [:])) as Map
        Map dest = (spec.destination ?: [:]) as Map
        Map st = (item.status ?: [:]) as Map
        Map op = (st.operationState ?: [:]) as Map
        ArgoApp a = new ArgoApp(
            name: item.metadata?.name,
            project: spec.project ?: 'default',
            repoUrl: src.repoURL,
            path: src.path,
            chart: src.chart,
            targetRevision: src.targetRevision ?: 'HEAD',
            destServer: dest.server,
            destName: dest.name,
            namespace: dest.namespace,
            syncStatus: st.sync?.status ?: 'Unknown',
            healthStatus: st.health?.status ?: 'Unknown',
            revision: st.sync?.revision,
            operationPhase: op.phase,
            operationMessage: op.message,
            reconciledAt: st.reconciledAt,
            lastSyncAt: op.finishedAt ?: op.startedAt,
            autoSync: spec.syncPolicy?.automated != null,
            autoPrune: spec.syncPolicy?.automated?.prune == true,
            selfHeal: spec.syncPolicy?.automated?.selfHeal == true,
            deleting: item.metadata?.deletionTimestamp != null,
            multiSource: spec.sources instanceof List && !spec.source,
            sourceType: st.sourceType ?: (src.chart || src.helm ? 'Helm' : src.kustomize ? 'Kustomize' : 'Directory'),
            helmValueFiles: ((src.helm?.valueFiles ?: []) as List).collect { it as String },
            helmParams: ((src.helm?.parameters ?: []) as List).collect { Map x -> [name: x.name, value: x.value] },
            kustomizeImages: ((src.kustomize?.images ?: []) as List).collect { it as String }
        )
        (st.resources ?: []).each { Map r ->
            a.resources << new ArgoResource(kind: r.kind, name: r.name, namespace: r.namespace, group: r.group ?: '',
                syncStatus: r.status ?: 'Unknown', healthStatus: r.health?.status ?: '')
        }
        ((st.history ?: []) as List).reverse().take(10).eachWithIndex { Map h, int i ->
            a.history << new ArgoHistory(id: h.id as String, revision: shortRev(h.revision as String),
                deployedAt: h.deployedAt as String, deployedAgo: ago(h.deployedAt as String), current: i == 0)
        }
        (st.conditions ?: []).each { Map c -> a.conditions << "${c.type}: ${c.message}".toString() }
        return a
    }

    // ---- display helpers (Handlebars cannot compute) ----
    boolean getHasView() { viewIndex != null }
    String getCheckedAgo() { ago(reconciledAt) ?: 'not yet' }
    boolean getIsHelm() { sourceType == 'Helm' }
    boolean getIsKustomize() { sourceType == 'Kustomize' }
    boolean getEditable() { !multiSource }
    String getValueFileList() { helmValueFiles.join(', ') }
    String getHelmParamText() { helmParams.collect { "${it.name}=${it.value}" }.join('\n') }
    String getImageText() { kustomizeImages.join('\n') }
    String getSource() { chart ? "${repoUrl} (chart ${chart})" : "${repoUrl}${path ? ' / ' + path : ''}" }
    String getShortRevision() { shortRev(revision) }
    String getLastSyncAgo() { ago(lastSyncAt) ?: 'never' }
    String getSyncClass() { chip(syncStatus == 'Synced' ? 'ok' : syncStatus == 'OutOfSync' ? 'warn' : 'unknown') }
    String getHealthClass() {
        switch (healthStatus) {
            case 'Healthy': return chip('ok')
            case 'Progressing': return chip('info')
            case 'Degraded': case 'Missing': return chip('bad')
            case 'Suspended': return chip('warn')
            default: return chip('unknown')
        }
    }
    boolean getOperationFailed() { operationPhase in ['Failed', 'Error'] }
    boolean getOperationRunning() { operationPhase in ['Running', 'Terminating'] }
    int getResourceCount() { resources.size() }
    /** HTML pattern for the type-to-confirm delete input. App names are DNS-1123 (a-z0-9 - .); only '.' needs escaping. */
    String getConfirmPattern() { (name ?: '').replace('.', '\\.') }

    static String chip(String level) { "argocd-chip argocd-chip-${level}" }

    static String shortRev(String rev) { rev ? (rev.length() > 8 && rev ==~ /[0-9a-f]+/ ? rev.take(8) : rev) : '' }

    static String ago(String iso) {
        if (!iso) return null
        try {
            Duration d = Duration.between(Instant.parse(iso), Instant.now())
            long s = Math.max(0L, d.seconds)
            if (s < 60) return "${s}s ago"
            if (s < 3600) return "${(long) (s / 60)}m ago"
            if (s < 86400) return "${(long) (s / 3600)}h ago"
            return "${(long) (s / 86400)}d ago"
        } catch (Exception ignored) {
            return iso
        }
    }
}

class ArgoResource {
    String group
    String kind
    String name
    String namespace
    String syncStatus
    String healthStatus
    /** Value used by the selective-sync checkboxes: group/kind/namespace/name. */
    String getRef() { "${group ?: ''}/${kind}/${namespace ?: ''}/${name}" }
    String getSyncClass() { ArgoApp.chip(syncStatus == 'Synced' ? 'ok' : syncStatus == 'OutOfSync' ? 'warn' : 'unknown') }
    String getHealthClass() {
        healthStatus == 'Healthy' ? ArgoApp.chip('ok') : healthStatus == 'Progressing' ? ArgoApp.chip('info') :
            healthStatus in ['Degraded', 'Missing'] ? ArgoApp.chip('bad') : ArgoApp.chip('unknown')
    }
}

class ArgoHistory {
    String id
    String revision
    String deployedAt
    String deployedAgo
    boolean current
}
