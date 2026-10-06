# Argo CD plugin for Morpheus

Adds an **Argo CD** tab to Kubernetes clusters in Morpheus (HKS or any other Kubernetes). From the tab you can
see the Argo CD applications running on that cluster and work with them without leaving Morpheus.

- Application list with sync and health status
- Resource tree (application → service / deployment → replica set → pod) with zoom
- Resource details: events, pod logs, live manifest
- Create, sync, refresh, hard refresh and delete applications
- Restart, sync or delete a single resource
- Morpheus role permission **Argo CD Applications**: none / read / full

## Walkthrough

[![Walkthrough: install, configure and use the Argo CD tab](https://github.com/NixndME/Morpheus-ArgoCD-Plugin/releases/download/v0.1.28/argocd-plugin-preview.gif)](https://github.com/NixndME/Morpheus-ArgoCD-Plugin/releases/download/v0.1.28/argocd-plugin-walkthrough.mp4)

The preview is one minute at normal speed: the Argo CD tab on a cluster and an application's resource tree.
[Watch the full walkthrough (MP4, about 7 minutes)](https://github.com/NixndME/Morpheus-ArgoCD-Plugin/releases/download/v0.1.28/argocd-plugin-walkthrough.mp4):
upload the plugin, set the Argo CD URL and token, give a role access, then open a cluster and create, sync,
explore, restart and delete an application.

## Which clusters get the tab

The tab only shows on clusters where Argo CD actually manages at least one application. Names and labels are
not used for matching. The plugin proves that an Argo CD destination is the same cluster by:

1. looking up an object Argo CD deployed (same uid) in the cluster, using the API access Morpheus already has
2. comparing the cluster CA of both API endpoints
3. comparing the API URL

## Install

1. Download `argocd-plugin.jar` from the releases page.
2. In Morpheus go to *Administration > Integrations > Plugins* and upload it.
3. Edit the plugin and set the Argo CD URL and an Argo CD API token (see [Argo CD account and token](#argo-cd-account-and-token-for-morpheus)).
4. In *Administration > Roles* open a role and set **Argo CD Applications** (in the Argo CD section) to read or full.
   System Admin gets full access automatically.

To upgrade, upload the new jar over the old one. This keeps the settings and role permissions.
Deleting the plugin and installing it again resets the permission on all roles.

## Argo CD account and token for Morpheus

Morpheus talks to Argo CD with an API token. Give it its own Argo CD account with only the rights it needs.

[![Create the Argo CD account and token](https://github.com/NixndME/Morpheus-ArgoCD-Plugin/releases/download/v0.1.28/argocd-account-preview.gif)](https://github.com/NixndME/Morpheus-ArgoCD-Plugin/releases/download/v0.1.28/argocd-account-token.mp4)

[Watch the whole step (MP4, about 4 minutes)](https://github.com/NixndME/Morpheus-ArgoCD-Plugin/releases/download/v0.1.28/argocd-account-token.mp4): terminal, Argo CD UI, then Morpheus.

**1. Add the account** (API key only, it cannot log in to the UI). Run this where `kubectl` reaches the cluster
that runs Argo CD:

```bash
kubectl -n argocd patch configmap argocd-cm --type merge -p '{"data":{"accounts.morpheus":"apiKey"}}'
```

**2. Give it a role.** Save this as `morpheus-rbac.yaml`:

```yaml
data:
  policy.csv: |
    p, role:morpheus, applications, *, */*, allow
    p, role:morpheus, clusters, get, *, allow
    p, role:morpheus, projects, get, *, allow
    p, role:morpheus, logs, get, */*, allow
    g, morpheus, role:morpheus
```

The patch replaces the whole `policy.csv`, so first copy the lines you already have into the file:

```bash
kubectl -n argocd get configmap argocd-rbac-cm -o jsonpath='{.data.policy\.csv}'
kubectl -n argocd patch configmap argocd-rbac-cm --type merge --patch-file morpheus-rbac.yaml
```

To limit Morpheus to some projects, use `my-project/*` instead of `*/*` in the `applications` and `logs` lines.
This role covers everything the plugin does (list, create, sync, refresh, delete, restart, logs, events) and
nothing else: it cannot change Argo CD settings, projects or repositories.

**3. Create the token.** In the Argo CD UI go to *Settings > Accounts > morpheus* and click *Generate New*.
Leave *Expires In* empty for no expiry, or set one such as `90d`. Copy the token, Argo CD shows it only once.
With the CLI: `argocd account generate-token --account morpheus`.

**4. Use it in Morpheus.** *Administration > Integrations > Plugins*, edit **Argo CD**, paste the token into
*Argo CD API token* and save. The Argo CD tab then shows `as morpheus` next to the connection.

## Audit

Every action is written to the Morpheus log with the user, the action, the application and the result,
including refused attempts. Syncs started from Morpheus show the Morpheus user in Argo CD, and applications
created from Morpheus carry the annotation `morpheus.argocd/created-by`.

## Build

Needs JDK 17 and Gradle 8.

```bash
cd plugin
gradle clean shadowJar test
```

The jar is written to `plugin/build/libs/`. Tested with Morpheus 9.0.2 and Argo CD 3.5.
