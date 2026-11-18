# Argo CD plugin for Morpheus

Adds an **Argo CD** tab to Kubernetes clusters in Morpheus (HKS or any other Kubernetes). From the tab you can
see the Argo CD applications running on that cluster and work with them without leaving Morpheus.

- Application list with sync and health status
- Resource tree (application → service / deployment → replica set → pod) with zoom
- Resource details: events, pod logs, live manifest
- Create, sync, refresh, hard refresh and delete applications
- Diff: what runs in the cluster compared with Git, per resource
- Sync options: revision, dry run, prune, force, or only selected resources
- Roll back to an earlier entry in History
- Edit an application: target revision, path, Helm values files and parameters, Kustomize images, automatic sync
- Resource actions that Argo CD offers, such as restart, pause, resume and scale; sync or delete a single resource
- Install Argo CD on an HKS cluster from Morpheus with one click, or connect an Argo CD you already have
- Several Argo CDs at once: one Argo CD integration each; a cluster tab shows a section for every Argo CD on it
- Morpheus role permissions **Argo CD Applications** (none / read / full) and **Argo CD Admin** (install, connect)

## Walkthrough

[![Walkthrough: install, configure and use the Argo CD tab](https://github.com/NixndME/Morpheus-ArgoCD-Plugin/releases/download/v0.2.0/argocd-plugin-preview.gif)](https://github.com/NixndME/Morpheus-ArgoCD-Plugin/releases/download/v0.2.0/argocd-plugin-walkthrough.mp4)

The preview is one minute at normal speed: the Argo CD tab on a cluster and an application's resource tree.
[Watch the full walkthrough (MP4)](https://github.com/NixndME/Morpheus-ArgoCD-Plugin/releases/download/v0.2.0/argocd-plugin-walkthrough.mp4):
upload the plugin, connect the Argo CD a cluster already has, install a second Argo CD on another HKS cluster,
give a role access, then open the cluster and create, sync, explore, restart and delete an application.

## Which clusters get the tab

The tab only shows on clusters where Argo CD actually manages at least one application. Names and labels are
not used for matching. The plugin proves that an Argo CD destination is the same cluster by:

1. looking up an object Argo CD deployed (same uid) in the cluster, using the API access Morpheus already has
2. comparing the cluster CA of both API endpoints
3. comparing the API URL

An Argo CD that runs on the cluster itself (for example one the plugin installed) also counts before it has any
application, so you can create the first one from the tab.

## Install

1. Download `argocd-plugin.jar` from the releases page.
2. In Morpheus go to *Administration > Integrations > Plugins* and upload it.
3. Go to *Administration > Integrations*, click *New Integration*, pick **Argo CD**, give it a name and save.
4. Open it. Pick one of the two ways:
   - **Install on an HKS cluster.** Every HKS cluster is listed. Press *Install* on one. The plugin installs
     Argo CD (the version bundled in the jar) in namespace `argocd`, opens port 30443 on the nodes, adds the
     `morpheus` account below, makes its token and connects. It tries the node addresses Morpheus knows,
     workers first, and keeps the one that answers. The *Connection* tab then shows the Argo CD address and the
     first `admin` password. Change that password in Argo CD.
     Before you press Install: the cluster nodes download the Argo CD images from the internet (quay.io,
     ghcr.io, public.ecr.aws), and Morpheus must reach the nodes on port 30443 (or the free port it picks).
     The installed Argo CD has a self-signed certificate, so *Check the certificate* starts off.
   - **Use an Argo CD you already have.** On the *Connection* tab type its address and an API token (see
     [Argo CD account and token](#argo-cd-account-and-token-for-morpheus)). The plugin tests them before saving.
5. In *Administration > Roles* open a role and set **Argo CD Applications** (in the Argo CD section) to read or full.
   **Argo CD Admin** lets a role use the integration page. System Admin gets both automatically.

**Several Argo CDs.** Add one Argo CD integration per Argo CD (for example one you already have and one the
plugin installs). Each keeps its own address and token in Morpheus Cypher. A cluster tab shows one section per
Argo CD that manages that cluster. An Argo CD added in the main tenant can be used by every tenant; one added in a
tenant only by that tenant. A tenant needs the **Integrations** permission and **Argo CD Admin** on its tenant role
and on the user's role to add its own. When you upgrade from a version with one Argo CD, the first Argo CD
integration of the main tenant that an administrator opens takes over the connection from the plugin settings.

*Remove* on the integration page deletes only an Argo CD this plugin installed. The applications it deployed keep
running. An Argo CD installed another way is never removed.

To upgrade, upload the new jar over the old one. This keeps the settings and role permissions.
Deleting the plugin and installing it again resets the permission on all roles and deletes its Argo CD
integrations. An Argo CD it installed stays on the cluster; a new integration of the main tenant can remove or
connect it.

## Argo CD account and token for Morpheus

Only needed for an Argo CD you already have; *Install* does all of this for you. Morpheus talks to Argo CD with
an API token. Give it its own Argo CD account with only the rights it needs.

[![Create the Argo CD account and token](https://github.com/NixndME/Morpheus-ArgoCD-Plugin/releases/download/v0.2.0/argocd-account-preview.gif)](https://github.com/NixndME/Morpheus-ArgoCD-Plugin/releases/download/v0.2.0/argocd-account-token.mp4)

[Watch the whole step (MP4, about 4 minutes)](https://github.com/NixndME/Morpheus-ArgoCD-Plugin/releases/download/v0.2.0/argocd-account-token.mp4): terminal, Argo CD UI, then Morpheus.

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

**4. Use it in Morpheus.** *Administration > Integrations > Argo CD*, *Connection* tab: type the Argo CD address,
paste the token and save. The Argo CD tab then shows `as morpheus` next to the connection.

## Audit

Every action is written to the Morpheus log with the user, the action, the application and the result,
including refused attempts. Syncs started from Morpheus show the Morpheus user in Argo CD, and applications
created from Morpheus carry the annotation `morpheus.argocd/created-by`. Install, Remove, Connect and Disconnect on
the integration page are logged too.

## Build

Needs JDK 17 and Gradle 8.

```bash
cd plugin
gradle clean shadowJar test
```

The jar is written to `plugin/build/libs/`. To bundle a newer Argo CD, run `python3 tools/argocd-manifest.py v3.x.y`
before the build. Tested with Morpheus 9.1.0.2 and Argo CD 3.5.6 (connected) and 3.6.1 (installed by the plugin) on
HKS clusters, and earlier with Morpheus 9.0.2 on a k3s cluster added as an External Kubernetes Cluster.
