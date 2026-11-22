package cl.duoc.eventomax.productions.integration.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Propiedades externas para la integración de solo lectura con Microsoft Graph.
 * Permanece DESACTIVADA por defecto ({@code enabled = false}) hasta que se concedan
 * los permisos mínimos en Entra ID y se provisionen credenciales seguras.
 */
@Component
@ConfigurationProperties(prefix = "eventomax.graph")
public class MicrosoftGraphProperties {

    private boolean enabled = false;
    private String tenantId = "";
    private String clientId = "";
    private String clientSecret = "";
    private String servicePrincipalId = "";
    private String organizerAppRoleId = "";
    private String tokenBaseUrl = "https://login.microsoftonline.com";
    private String graphBaseUrl = "https://graph.microsoft.com/v1.0";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(5);
    private Duration cacheTtl = Duration.ofMinutes(5);
    private int maxPages = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getServicePrincipalId() {
        return servicePrincipalId;
    }

    public void setServicePrincipalId(String servicePrincipalId) {
        this.servicePrincipalId = servicePrincipalId;
    }

    public String getOrganizerAppRoleId() {
        return organizerAppRoleId;
    }

    public void setOrganizerAppRoleId(String organizerAppRoleId) {
        this.organizerAppRoleId = organizerAppRoleId;
    }

    public String getTokenBaseUrl() {
        return tokenBaseUrl;
    }

    public void setTokenBaseUrl(String tokenBaseUrl) {
        this.tokenBaseUrl = tokenBaseUrl;
    }

    public String getGraphBaseUrl() {
        return graphBaseUrl;
    }

    public void setGraphBaseUrl(String graphBaseUrl) {
        this.graphBaseUrl = graphBaseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public boolean isConfigured() {
        return hasText(tenantId)
                && hasText(clientId)
                && hasText(clientSecret)
                && hasText(servicePrincipalId)
                && hasText(organizerAppRoleId);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
