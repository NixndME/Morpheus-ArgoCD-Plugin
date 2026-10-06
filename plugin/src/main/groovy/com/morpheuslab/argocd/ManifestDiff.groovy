package com.morpheuslab.argocd

import groovy.json.JsonSlurper

/** Git-vs-live diff of one resource, shown as YAML lines like Argo CD's DIFF tab. */
class ManifestDiff {

    static final int CONTEXT = 3
    static final int MAX_LINES = 600

    /** Rows of [cls: add|del|ctx|gap, text]; empty when both sides are the same. */
    static List<Map> of(String targetJson, String liveJson) {
        List<String> want = yaml(clean(parse(targetJson)))
        List<String> have = yaml(clean(parse(liveJson)))
        if (want == have) return []
        unified(have.take(MAX_LINES), want.take(MAX_LINES))
    }

    static Object parse(String json) {
        if (!json?.trim()) return null
        try { new JsonSlurper().parseText(json) } catch (Exception ignored) { null }
    }

    /** Drop what Kubernetes fills in itself, so only meaningful differences remain. */
    static Object clean(Object doc) {
        if (!(doc instanceof Map)) return doc
        Map m = new LinkedHashMap(doc as Map)
        m.remove('status')
        if (m.metadata instanceof Map) {
            Map md = new LinkedHashMap(m.metadata as Map)
            ['managedFields', 'resourceVersion', 'uid', 'creationTimestamp', 'generation', 'selfLink'].each { md.remove(it) }
            if (md.annotations instanceof Map) {
                Map an = new LinkedHashMap(md.annotations as Map)
                ['kubectl.kubernetes.io/last-applied-configuration', 'deployment.kubernetes.io/revision'].each { an.remove(it) }
                if (an) md.annotations = an else md.remove('annotations')
            }
            m.metadata = md
        }
        m
    }

    /** Live manifest as YAML, like Argo CD shows it (managedFields and last-applied hidden). */
    static String manifestYaml(String json) {
        Object doc = parse(json)
        if (!(doc instanceof Map)) return json
        Map m = new LinkedHashMap(doc as Map)
        if (m.metadata instanceof Map) {
            Map md = new LinkedHashMap(m.metadata as Map)
            md.remove('managedFields')
            if (md.annotations instanceof Map) {
                Map an = new LinkedHashMap(md.annotations as Map)
                an.remove('kubectl.kubernetes.io/last-applied-configuration')
                if (an) md.annotations = an else md.remove('annotations')
            }
            m.metadata = md
        }
        yaml(m).join('\n')
    }

    /** A small YAML writer (maps, lists, scalars) — enough to read a manifest. */
    static List<String> yaml(Object o, String indent = '') {
        List<String> out = []
        if (o instanceof Map) {
            (o as Map).each { k, v ->
                if (v instanceof Map && v) { out << "${indent}${k}:".toString(); out.addAll(yaml(v, indent + '  ')) }
                else if (v instanceof List && v) { out << "${indent}${k}:".toString(); out.addAll(yaml(v, indent)) }
                else out << "${indent}${k}: ${scalar(v)}".toString()
            }
        } else if (o instanceof List) {
            (o as List).each { v ->
                if (v instanceof Map && v) {
                    List<String> inner = yaml(v, indent + '  ')
                    out << "${indent}- ${inner[0].trim()}".toString()
                    out.addAll(inner.drop(1))
                } else if (v instanceof List && v) {
                    out << "${indent}-".toString(); out.addAll(yaml(v, indent + '  '))
                } else out << "${indent}- ${scalar(v)}".toString()
            }
        } else if (o != null) {
            out << "${indent}${scalar(o)}".toString()
        }
        out
    }

    private static String scalar(Object v) {
        if (v == null) return 'null'
        if (v instanceof Map) return '{}'
        if (v instanceof List) return '[]'
        if (v instanceof Number || v instanceof Boolean) return v.toString()
        String s = v.toString()
        s.contains('\n') ? s.readLines().join('\\n') : (s ==~ /[\w.\/:@-]+/ && !(s in ['true', 'false', 'null', 'yes', 'no']) ? s : '"' + s.replace('"', '\\"') + '"')
    }

    /** Line diff (longest common subsequence) shown with a few lines of context around each change. */
    static List<Map> unified(List<String> a, List<String> b, int ctx = CONTEXT) {
        int n = a.size(), m = b.size()
        int[][] lcs = new int[n + 1][m + 1]
        for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--)
            lcs[i][j] = a[i] == b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1])
        List<Map> all = []
        int i = 0, j = 0
        while (i < n || j < m) {
            if (i < n && j < m && a[i] == b[j]) { all << [cls: 'ctx', text: '  ' + a[i]]; i++; j++ }
            else if (i < n && (j >= m || lcs[i + 1][j] >= lcs[i][j + 1])) { all << [cls: 'del', text: '- ' + a[i]]; i++ }
            else { all << [cls: 'add', text: '+ ' + b[j]]; j++ }
        }
        Set<Integer> keep = [] as Set
        all.eachWithIndex { Map r, int k -> if (r.cls != 'ctx') ((Math.max(0, k - ctx))..(Math.min(all.size() - 1, k + ctx))).each { keep << it } }
        List<Map> out = []
        int last = -2
        keep.sort().each { int k ->
            if (k != last + 1 && !out.isEmpty()) out << [cls: 'gap', text: '  …']
            out << all[k]; last = k
        }
        out
    }
}
