package com.morpheuslab.argocd

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.AbstractGenericIntegrationProvider
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/** Administration > Integrations > Argo CD: install Argo CD on an HKS cluster or connect to one you have. */
@Slf4j
class ArgoCdIntegrationProvider extends AbstractGenericIntegrationProvider {

    Plugin plugin
    MorpheusContext morpheus

    ArgoCdIntegrationProvider(Plugin plugin, MorpheusContext morpheus) { this.plugin = plugin; this.morpheus = morpheus }

    String getCode() { 'argocd-integration' }
    String getName() { 'Argo CD' }
    MorpheusContext getMorpheus() { morpheus }
    Plugin getPlugin() { plugin }

    String getCategory() { 'other' }
    List<OptionType> getOptionTypes() { [] }
    void refresh(AccountIntegration integration) { }
    Icon getIcon() { new Icon(path: 'argocd.svg', darkPath: 'argocd-dark.svg') }

    HTMLResponse renderTemplate(AccountIntegration integration) {
        ViewModel<SetupPage> m = new ViewModel<>()
        try {
            m.object = SetupPage.build(morpheus, plugin, integration)
        } catch (Throwable t) {
            log.error('ArgoCD: setup page failed', t)
            m.object = new SetupPage(integrationId: integration?.id, error: "The page failed to load: ${t.message}")
        }
        plugin.renderer.renderTemplate('hbs/setup', m)
    }
}
