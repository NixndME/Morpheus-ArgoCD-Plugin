package com.morpheuslab.argocd

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HandlebarsRenderer
import groovy.util.logging.Slf4j

/**
 * Argo CD for Morpheus. Connection (URL + API token) is configured once in the plugin settings;
 * the cluster tab and controller routes read it through ArgoCdSettings.
 */
@Slf4j
class ArgoCdPlugin extends Plugin {

    /** View apps and status. `full` additionally allows sync / refresh / create / delete. */
    static final String PERMISSION_APPS = 'argocd-apps'

    @Override
    String getCode() { 'argocd' }

    @Override
    List<OptionType> getSettings() {
        [
            new OptionType(
                name: 'Argo CD URL', code: 'argocd.url', fieldName: ArgoCdSettings.URL_FIELD,
                fieldLabel: 'Argo CD URL', inputType: OptionType.InputType.TEXT, required: false, displayOrder: 0,
                helpText: 'The Argo CD server, e.g. https://argocd.example.com or https://10.0.0.5:30443.'
            ),
            new OptionType(
                name: 'Argo CD API token', code: 'argocd.token', fieldName: ArgoCdSettings.TOKEN_FIELD,
                fieldLabel: 'Argo CD API token', inputType: OptionType.InputType.PASSWORD, required: false, displayOrder: 1,
                helpText: 'A token for an Argo CD account with apiKey capability ' +
                    '(argocd account generate-token --account morpheus). Scope its Argo CD RBAC role to what Morpheus may do.'
            ),
            new OptionType(
                name: 'Verify TLS certificate', code: 'argocd.verifyTls', fieldName: ArgoCdSettings.VERIFY_TLS_FIELD,
                fieldLabel: 'Verify TLS certificate', inputType: OptionType.InputType.CHECKBOX, defaultValue: 'on',
                required: false, displayOrder: 2,
                helpText: 'Turn off only for a lab Argo CD with a self-signed certificate. Affects this plugin only.'
            ),
            new OptionType(
                name: 'Default project', code: 'argocd.defaultProject', fieldName: ArgoCdSettings.PROJECT_FIELD,
                fieldLabel: 'Default Argo CD project', inputType: OptionType.InputType.TEXT, defaultValue: 'default',
                required: false, displayOrder: 3,
                helpText: 'Pre-selected AppProject in the New Application form.'
            )
        ]
    }

    @Override
    void initialize() {
        setName('Argo CD')
        setDescription('Manage Argo CD applications from Morpheus')
        // Morpheus 9.0.2 bug: a plugin WITH controllers and WITHOUT its own renderer gets its template loader
        // added to the shared renderer, whose loader list is Arrays.asList (fixed size) -> the whole plugin fails
        // to load ("Failed to parse plugin file"). Owning a renderer skips that path (PluginManager.registerPlugin).
        HandlebarsRenderer renderer = new HandlebarsRenderer('renderer', getClassLoader())
        renderer.registerAssetHelper(getName())
        renderer.registerNonceHelper(morpheus.getWebRequest())
        renderer.registerI18nHelper(this, morpheus)
        setRenderer(renderer)
        setPermissions([
            Permission.build('Argo CD Applications', PERMISSION_APPS,
                [Permission.AccessType.none, Permission.AccessType.read, Permission.AccessType.full])
        ])
        registerProvider(new ArgoCdClusterTabProvider(this, morpheus))
        controllers.add(new ArgoCdController(this, morpheus))
    }

    /** Exposed for the controller, which renders its own pages. */
    HandlebarsRenderer pageRenderer() { (HandlebarsRenderer) renderer
    }

    @Override
    void onDestroy() {}
}
