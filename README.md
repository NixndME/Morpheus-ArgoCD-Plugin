# Argo CD plugin for Morpheus

Adds an **Argo CD** tab to Kubernetes clusters in Morpheus (HKS or any other Kubernetes). From the tab you can
see the Argo CD applications running on that cluster and work with them without leaving Morpheus.

- Application list with sync and health status
- Resource tree (application → service / deployment → replica set → pod) with zoom
- Resource details: events, pod logs, live manifest
- Create, sync, refresh, hard refresh and delete applications
- Restart, sync or delete a single resource
- Morpheus role permission **Argo CD Applications**: none / read / full

## Which clusters get the tab

The tab only shows on clusters where Argo CD actually manages at least one application. Names and labels are
not used for matching. The plugin proves that an Argo CD destination is the same cluster by:

1. looking up an object Argo CD deployed (same uid) in the cluster, using the API access Morpheus already has
2. comparing the cluster CA of both API endpoints
3. comparing the API URL

## Install

1. Download `argocd-plugin.jar` from the releases page.
2. In Morpheus go to *Administration > Integrations > Plugins* and upload it.
3. Edit the plugin and set the Argo CD URL and an Argo CD API token.
4. In *Administration > Roles* set **Argo CD Applications** to read or full for the roles that need it.
   System Admin gets full access automatically.

To upgrade, upload the new jar over the old one. This keeps the settings and role permissions.
Deleting the plugin and installing it again resets the permission on all roles.

## Argo CD token

Create a local Argo CD account with API key access, for example in `argocd-cm`:

```yaml
data:
  accounts.morpheus: apiKey
```

Give it a role in `argocd-rbac-cm` and create a token with `argocd account generate-token --account morpheus`.

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
