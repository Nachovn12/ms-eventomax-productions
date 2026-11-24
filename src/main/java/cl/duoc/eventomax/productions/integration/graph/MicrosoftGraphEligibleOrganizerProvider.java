package cl.duoc.eventomax.productions.integration.graph;

import cl.duoc.eventomax.productions.dto.DirectorySyncStatus;
import cl.duoc.eventomax.productions.dto.EligibleOrganizerSource;
import cl.duoc.eventomax.productions.security.SecurityUtils;
import cl.duoc.eventomax.productions.service.DirectoryFetchResult;
import cl.duoc.eventomax.productions.service.EligibleOrganizerCandidate;
import cl.duoc.eventomax.productions.service.EligibleOrganizerProvider;
import cl.duoc.eventomax.productions.service.ExternalDirectoryUnavailableException;
import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adaptador de solo lectura hacia Microsoft Graph REST v1.0 para obtener y verificar
 * usuarios habilitados (Member o Guest) asignados al App Role {@code Organizador} de {@code eventomax-api}.
 * <p>
 * Reglas de seguridad y resiliencia:
 * - Desactivado por defecto ({@code eventomax.graph.enabled=false}); no realiza llamadas reales si está desactivado.
 * - Usa exclusivamente Client Credentials backend; jamás usa tokens del frontend ni registra tokens o PII en logs.
 * - Acepta usuarios {@code Member} y {@code Guest} habilitados ({@code accountEnabled = true}), descartando UPNs {@code #EXT#}.
 * - Actualmente solo procesa asignaciones directas {@code principalType == "User"} (0 grupos asignados en el tenant).
 *   Extensión futura para grupos: resolver {@code GET /groups/{id}/members} (requerirá permiso {@code GroupMember.Read.All}).
 * - En verificación para creación ({@link #verifyEligibleOrganizer}), falla cerrado lanzando
 *   {@link ExternalDirectoryUnavailableException} si Graph está desactivado o no responde; nunca autoriza desde caché obsoleta.
 */
@Component
public class MicrosoftGraphEligibleOrganizerProvider implements EligibleOrganizerProvider, EntraUserDisplayNameClient {

    private static final Logger log = LoggerFactory.getLogger(MicrosoftGraphEligibleOrganizerProvider.class);
    private static final long MAX_RETRY_AFTER_MS = 2_000L;
    private static final int MAX_BATCH_SIZE = 20;
    private static final int MAX_USER_CACHE_ENTRIES = 1_000;
    private static final Duration NEGATIVE_CACHE_TTL = Duration.ofMinutes(2);

    private record CachedToken(String accessToken, Instant expiresAt) {}
    private record CachedDirectory(List<EligibleOrganizerCandidate> candidates, Instant expiresAt) {}
    private record CachedUserDisplayName(Optional<String> displayName, Instant expiresAt) {}

    private final MicrosoftGraphProperties properties;
    private final RestClient restClient;
    private final AtomicReference<CachedToken> tokenCache = new AtomicReference<>();
    private final AtomicReference<CachedDirectory> directoryCache = new AtomicReference<>();
    private final ConcurrentHashMap<String, CachedUserDisplayName> userDisplayNameCache = new ConcurrentHashMap<>();

    @Autowired
    public MicrosoftGraphEligibleOrganizerProvider(MicrosoftGraphProperties properties) {
        this(properties, buildRestClient(properties));
    }

    public MicrosoftGraphEligibleOrganizerProvider(MicrosoftGraphProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    private static RestClient buildRestClient(MicrosoftGraphProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(props.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.getReadTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public EligibleOrganizerSource source() {
        return EligibleOrganizerSource.ENTRA_ORGANIZER;
    }

    @Override
    public DirectoryFetchResult fetchEligibleOrganizers() {
        if (!properties.isEnabled()) {
            return new DirectoryFetchResult(List.of(), DirectorySyncStatus.DISABLED, false);
        }
        if (!properties.isConfigured()) {
            log.warn("[GRAPH_MISCONFIGURED] eventomax.graph.enabled=true pero faltan propiedades requeridas de configuración.");
            return new DirectoryFetchResult(List.of(), DirectorySyncStatus.DEGRADED, false);
        }

        CachedDirectory cached = directoryCache.get();
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return new DirectoryFetchResult(cached.candidates(), DirectorySyncStatus.OK, true);
        }

        try {
            List<EligibleOrganizerCandidate> fresh = fetchLiveEligibleOrganizers();
            Instant expiry = Instant.now().plus(properties.getCacheTtl());
            directoryCache.set(new CachedDirectory(List.copyOf(fresh), expiry));
            return new DirectoryFetchResult(List.copyOf(fresh), DirectorySyncStatus.OK, true);
        } catch (Exception ex) {
            log.warn("[GRAPH_DEGRADED] No fue posible sincronizar organizadores elegibles desde Microsoft Graph: {}",
                    ex.getClass().getSimpleName());
            return new DirectoryFetchResult(List.of(), DirectorySyncStatus.DEGRADED, false);
        }
    }

    @Override
    public Optional<EligibleOrganizerCandidate> verifyEligibleOrganizer(String organizerId) {
        if (!properties.isEnabled()) {
            throw new ExternalDirectoryUnavailableException(
                    "La integración con Microsoft Graph está desactivada; no es posible verificar la elegibilidad del organizador Entra ID.");
        }
        if (!properties.isConfigured()) {
            throw new ExternalDirectoryUnavailableException(
                    "La configuración de Microsoft Graph está incompleta; no es posible verificar la elegibilidad.");
        }
        if (organizerId == null || !SecurityUtils.isCanonicalOwnerId(organizerId.trim())) {
            return Optional.empty();
        }
        String normalized = organizerId.trim().toLowerCase(Locale.ROOT);
        String[] parts = normalized.split(":");
        String requestedTid = parts[0];
        String requestedOid = parts[1];

        if (!properties.getTenantId().trim().equalsIgnoreCase(requestedTid)) {
            return Optional.empty();
        }

        try {
            String token = acquireAccessToken();
            Set<String> assignedUserIds = fetchAssignedOrganizerUserIds(token);
            if (!assignedUserIds.contains(requestedOid)) {
                return Optional.empty();
            }
            return fetchEnabledUserCandidate(token, requestedOid);
        } catch (ExternalDirectoryUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("[GRAPH_VERIFY_FAILED] Fallo al verificar elegibilidad Entra ID: {}", ex.getClass().getSimpleName());
            throw new ExternalDirectoryUnavailableException(
                    "No fue posible verificar la elegibilidad del organizador en Microsoft Entra ID.", ex);
        }
    }

    public void clearCache() {
        directoryCache.set(null);
        tokenCache.set(null);
        userDisplayNameCache.clear();
    }

    @Override
    public boolean isUserLookupReady() {
        return properties.isEnabled() && properties.isUserLookupConfigured();
    }

    @Override
    public String getConfiguredTenantId() {
        return properties.getTenantId() != null ? properties.getTenantId().trim().toLowerCase(Locale.ROOT) : "";
    }

    @Override
    public Map<String, Optional<String>> lookupUserDisplayNames(Set<String> normalizedObjectIds) {
        Map<String, Optional<String>> results = new LinkedHashMap<>();
        if (normalizedObjectIds == null || normalizedObjectIds.isEmpty()) {
            return results;
        }
        if (!isUserLookupReady()) {
            for (String oid : normalizedObjectIds) {
                results.put(oid, Optional.empty());
            }
            return results;
        }

        Instant now = Instant.now();
        List<String> uncached = new ArrayList<>();
        for (String rawOid : normalizedObjectIds) {
            if (rawOid == null || rawOid.isBlank()) {
                continue;
            }
            String oid = rawOid.trim().toLowerCase(Locale.ROOT);
            CachedUserDisplayName cached = userDisplayNameCache.get(oid);
            if (cached != null && now.isBefore(cached.expiresAt())) {
                results.put(oid, cached.displayName());
            } else {
                if (cached != null) {
                    userDisplayNameCache.remove(oid, cached);
                }
                uncached.add(oid);
            }
        }

        if (uncached.isEmpty()) {
            return results;
        }

        String token;
        try {
            token = acquireAccessToken();
        } catch (Exception ex) {
            log.warn("[GRAPH_USER_LOOKUP_TOKEN_FAILED] No se pudo obtener token para consultar nombres de organizadores: {}",
                    ex.getClass().getSimpleName());
            for (String oid : uncached) {
                results.put(oid, Optional.empty());
            }
            return results;
        }

        if (uncached.size() == 1) {
            String oid = uncached.get(0);
            results.put(oid, fetchSingleUserDisplayName(token, oid));
            return results;
        }

        for (int start = 0; start < uncached.size(); start += MAX_BATCH_SIZE) {
            List<String> chunk = uncached.subList(start, Math.min(start + MAX_BATCH_SIZE, uncached.size()));
            Map<String, Optional<String>> batchResults = fetchBatchUserDisplayNames(token, chunk);
            results.putAll(batchResults);
        }
        return results;
    }

    private Optional<String> fetchSingleUserDisplayName(String token, String oid) {
        String baseUrl = trimTrailingSlash(properties.getGraphBaseUrl());
        URI uri = URI.create(baseUrl + "/users/" + oid + "?$select=id,displayName,accountEnabled");
        try {
            JsonNode user = executeGraphGetWithRetry(uri, token);
            if (user == null) {
                putUserDisplayNameCache(oid, Optional.empty(), NEGATIVE_CACHE_TTL);
                return Optional.empty();
            }
            Optional<String> resolved = extractEnabledDisplayName(user);
            putUserDisplayNameCache(oid, resolved, resolved.isPresent() ? properties.getCacheTtl() : NEGATIVE_CACHE_TTL);
            return resolved;
        } catch (Exception ex) {
            log.warn("[GRAPH_USER_LOOKUP_DEGRADED] Fallo al consultar organizador en Microsoft Graph: {}",
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Map<String, Optional<String>> fetchBatchUserDisplayNames(String token, List<String> oids) {
        Map<String, Optional<String>> resolvedMap = new LinkedHashMap<>();
        for (String oid : oids) {
            resolvedMap.put(oid, Optional.empty());
        }

        String baseUrl = trimTrailingSlash(properties.getGraphBaseUrl());
        URI batchUri = URI.create(baseUrl + "/$batch");

        List<Map<String, String>> requests = new ArrayList<>(oids.size());
        Map<String, String> idToOid = new LinkedHashMap<>(oids.size());
        for (int i = 0; i < oids.size(); i++) {
            String reqId = String.valueOf(i);
            String oid = oids.get(i);
            idToOid.put(reqId, oid);
            requests.add(Map.of(
                    "id", reqId,
                    "method", "GET",
                    "url", "/users/" + oid + "?$select=id,displayName,accountEnabled"
            ));
        }

        try {
            JsonNode batchResponse = executeGraphPostWithRetry(batchUri, token, Map.of("requests", requests));
            if (batchResponse == null || !batchResponse.path("responses").isArray()) {
                return resolvedMap;
            }
            for (JsonNode sub : batchResponse.path("responses")) {
                String reqId = sub.path("id").asText("");
                String oid = idToOid.get(reqId);
                if (oid == null) {
                    continue;
                }
                int status = sub.path("status").asInt(0);
                if (status == 200) {
                    Optional<String> displayName = extractEnabledDisplayName(sub.path("body"));
                    putUserDisplayNameCache(oid, displayName, displayName.isPresent() ? properties.getCacheTtl() : NEGATIVE_CACHE_TTL);
                    resolvedMap.put(oid, displayName);
                } else if (status == 404) {
                    putUserDisplayNameCache(oid, Optional.empty(), NEGATIVE_CACHE_TTL);
                    resolvedMap.put(oid, Optional.empty());
                } else if (status == 401 || status == 403) {
                    tokenCache.set(null);
                    log.warn("[GRAPH_BATCH_SUB_AUTH] Sub-consulta Microsoft Graph respondió HTTP {}", status);
                    resolvedMap.put(oid, Optional.empty());
                } else {
                    log.warn("[GRAPH_BATCH_SUB_ERROR] Sub-consulta Microsoft Graph respondió HTTP {}", status);
                    resolvedMap.put(oid, Optional.empty());
                }
            }
        } catch (Exception ex) {
            log.warn("[GRAPH_BATCH_LOOKUP_DEGRADED] Fallo en consulta por lote Microsoft Graph: {}",
                    ex.getClass().getSimpleName());
        }
        return resolvedMap;
    }

    private Optional<String> extractEnabledDisplayName(JsonNode user) {
        if (user == null || user.isNull() || user.isMissingNode()) {
            return Optional.empty();
        }
        boolean accountEnabled = user.path("accountEnabled").asBoolean(false);
        if (!accountEnabled) {
            return Optional.empty();
        }
        String displayName = user.path("displayName").asText("").trim();
        if (displayName.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(displayName.length() > 150 ? displayName.substring(0, 150) : displayName);
    }

    private void putUserDisplayNameCache(String oid, Optional<String> displayName, Duration ttl) {
        if (userDisplayNameCache.size() >= MAX_USER_CACHE_ENTRIES) {
            Instant now = Instant.now();
            userDisplayNameCache.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
            if (userDisplayNameCache.size() >= MAX_USER_CACHE_ENTRIES) {
                userDisplayNameCache.clear();
            }
        }
        Duration effectiveTtl = (ttl != null && !ttl.isNegative() && !ttl.isZero()) ? ttl : Duration.ofMinutes(5);
        userDisplayNameCache.put(oid, new CachedUserDisplayName(displayName, Instant.now().plus(effectiveTtl)));
    }

    private JsonNode executeGraphPostWithRetry(URI uri, String token, Object body) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return restClient.post()
                        .uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .onStatus(status -> status.is3xxRedirection(), (req, resp) -> {
                            throw new ExternalDirectoryUnavailableException(
                                    "Redirección HTTP bloqueada hacia Microsoft Graph (HTTP " + resp.getStatusCode().value() + ").");
                        })
                        .body(JsonNode.class);
            } catch (HttpClientErrorException ex) {
                int status = ex.getStatusCode().value();
                if (status == 429 && attempt == 0) {
                    long delayMs = resolveRetryAfterMs(ex.getResponseHeaders());
                    sleepQuietly(delayMs);
                    continue;
                }
                if (status == 401 || status == 403) {
                    tokenCache.set(null);
                }
                throw new ExternalDirectoryUnavailableException("Microsoft Graph respondió HTTP " + status, ex);
            } catch (HttpServerErrorException ex) {
                throw new ExternalDirectoryUnavailableException(
                        "Microsoft Graph respondió error temporal HTTP " + ex.getStatusCode().value(), ex);
            }
        }
        throw new ExternalDirectoryUnavailableException("Microsoft Graph excedió el límite de reintentos (HTTP 429).");
    }

    private List<EligibleOrganizerCandidate> fetchLiveEligibleOrganizers() {
        String token = acquireAccessToken();
        Set<String> assignedUserIds = fetchAssignedOrganizerUserIds(token);
        List<EligibleOrganizerCandidate> result = new ArrayList<>();
        for (String oid : assignedUserIds) {
            fetchEnabledUserCandidate(token, oid).ifPresent(result::add);
        }
        return result;
    }

    private Set<String> fetchAssignedOrganizerUserIds(String token) {
        Set<String> userIds = new LinkedHashSet<>();
        String baseUrl = trimTrailingSlash(properties.getGraphBaseUrl());
        String nextUrl = baseUrl + "/servicePrincipals/" + properties.getServicePrincipalId().trim()
                + "/appRoleAssignedTo?$select=appRoleId,principalId,principalDisplayName,principalType";

        int pages = 0;
        int maxPages = Math.max(1, properties.getMaxPages());
        String expectedRoleId = properties.getOrganizerAppRoleId().trim();

        while (nextUrl != null && pages < maxPages) {
            pages++;
            URI validatedUri = validateGraphUri(nextUrl);
            JsonNode body = executeGraphGetWithRetry(validatedUri, token);
            if (body == null) {
                break;
            }
            JsonNode values = body.path("value");
            if (values.isArray()) {
                for (JsonNode item : values) {
                    String roleId = item.path("appRoleId").asText("");
                    String principalType = item.path("principalType").asText("");
                    String principalId = item.path("principalId").asText("").trim().toLowerCase(Locale.ROOT);
                    if (!expectedRoleId.equalsIgnoreCase(roleId)) {
                        continue;
                    }
                    if ("User".equalsIgnoreCase(principalType) && !principalId.isBlank()) {
                        userIds.add(principalId);
                    } else if ("Group".equalsIgnoreCase(principalType)) {
                        log.info("[GRAPH_GROUP_SKIPPED] Asignación de grupo detectada en appRoleAssignedTo; expansión de grupos no habilitada en Etapa 2.");
                    }
                }
            }
            JsonNode nextLink = body.get("@odata.nextLink");
            nextUrl = (nextLink != null && !nextLink.isNull() && !nextLink.asText().isBlank())
                    ? nextLink.asText().trim()
                    : null;
        }
        return userIds;
    }

    URI validateGraphUri(String rawUrl) {
        URI base;
        URI candidate;
        try {
            base = URI.create(trimTrailingSlash(properties.getGraphBaseUrl()));
            candidate = URI.create(rawUrl);
        } catch (IllegalArgumentException ex) {
            throw new ExternalDirectoryUnavailableException("URL de paginación de Microsoft Graph inválida.");
        }
        if (!candidate.isAbsolute() || candidate.getScheme() == null || candidate.getHost() == null || candidate.getUserInfo() != null) {
            throw new ExternalDirectoryUnavailableException("URL de paginación de Microsoft Graph no permitida.");
        }
        if ("https".equalsIgnoreCase(base.getScheme()) && !"https".equalsIgnoreCase(candidate.getScheme())) {
            throw new ExternalDirectoryUnavailableException("Downgrade de HTTPS bloqueado en paginación de Microsoft Graph.");
        }
        if (!base.getScheme().equalsIgnoreCase(candidate.getScheme())
                || !base.getHost().equalsIgnoreCase(candidate.getHost())
                || effectivePort(base) != effectivePort(candidate)) {
            log.warn("[GRAPH_NEXTLINK_BLOCKED] Se rechazó @odata.nextLink con origen distinto al configurado.");
            throw new ExternalDirectoryUnavailableException("Origen no autorizado en paginación de Microsoft Graph.");
        }
        return candidate;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : ("http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1);
    }

    private Optional<EligibleOrganizerCandidate> fetchEnabledUserCandidate(String token, String oid) {
        String baseUrl = trimTrailingSlash(properties.getGraphBaseUrl());
        URI uri = URI.create(baseUrl + "/users/" + oid + "?$select=id,displayName,accountEnabled,userType,mail");
        JsonNode user = executeGraphGetWithRetry(uri, token);
        if (user == null) {
            return Optional.empty();
        }
        boolean accountEnabled = user.path("accountEnabled").asBoolean(false);
        if (!accountEnabled) {
            return Optional.empty();
        }
        String resolvedOid = user.path("id").asText(oid).trim().toLowerCase(Locale.ROOT);
        String canonicalId = properties.getTenantId().trim().toLowerCase(Locale.ROOT) + ":" + resolvedOid;
        String displayName = user.path("displayName").asText("").trim();
        if (displayName.isBlank()) {
            displayName = "Organizador EventoMax";
        }
        // Política explícita: solo atributo 'mail' (validado sintácticamente, excluyendo #EXT#).
        // No se utiliza userPrincipalName para evitar direcciones técnicas #EXT# de usuarios Guest.
        String rawMail = user.path("mail").isNull() ? null : user.path("mail").asText(null);
        String deliverableEmail = SecurityUtils.isDeliverableEmail(rawMail) ? rawMail.trim() : null;

        return Optional.of(new EligibleOrganizerCandidate(
                canonicalId,
                displayName,
                EligibleOrganizerSource.ENTRA_ORGANIZER,
                resolvedOid,
                deliverableEmail
        ));
    }

    private JsonNode executeGraphGetWithRetry(URI uri, String token) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return restClient.get()
                        .uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .onStatus(status -> status.is3xxRedirection(), (req, resp) -> {
                            throw new ExternalDirectoryUnavailableException(
                                    "Redirección HTTP bloqueada hacia Microsoft Graph (HTTP " + resp.getStatusCode().value() + ").");
                        })
                        .body(JsonNode.class);
            } catch (HttpClientErrorException ex) {
                int status = ex.getStatusCode().value();
                if (status == 429 && attempt == 0) {
                    long delayMs = resolveRetryAfterMs(ex.getResponseHeaders());
                    sleepQuietly(delayMs);
                    continue;
                }
                if (status == 404) {
                    return null;
                }
                if (status == 401 || status == 403) {
                    tokenCache.set(null);
                }
                throw new ExternalDirectoryUnavailableException("Microsoft Graph respondió HTTP " + status, ex);
            } catch (HttpServerErrorException ex) {
                throw new ExternalDirectoryUnavailableException(
                        "Microsoft Graph respondió error temporal HTTP " + ex.getStatusCode().value(), ex);
            }
        }
        throw new ExternalDirectoryUnavailableException("Microsoft Graph excedió el límite de reintentos (HTTP 429).");
    }

    private String acquireAccessToken() {
        CachedToken cached = tokenCache.get();
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.accessToken();
        }
        String tokenUrl = trimTrailingSlash(properties.getTokenBaseUrl()) + "/"
                + properties.getTenantId().trim() + "/oauth2/v2.0/token";

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.getClientId().trim());
        form.add("client_secret", properties.getClientSecret());
        form.add("scope", "https://graph.microsoft.com/.default");
        form.add("grant_type", "client_credentials");

        JsonNode response = restClient.post()
                .uri(URI.create(tokenUrl))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.hasNonNull("access_token")) {
            throw new ExternalDirectoryUnavailableException("No se pudo obtener token de servicio para Microsoft Graph.");
        }
        String accessToken = response.get("access_token").asText();
        long expiresIn = response.path("expires_in").asLong(3600L);
        Instant expiresAt = Instant.now().plusSeconds(Math.max(30L, expiresIn - 60L));
        tokenCache.set(new CachedToken(accessToken, expiresAt));
        return accessToken;
    }

    private static long resolveRetryAfterMs(HttpHeaders headers) {
        if (headers == null) {
            return 250L;
        }
        String retryAfter = headers.getFirst("Retry-After");
        if (retryAfter != null && !retryAfter.isBlank()) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                return Math.min(MAX_RETRY_AFTER_MS, Math.max(50L, seconds * 1000L));
            } catch (NumberFormatException ignored) {
                return 250L;
            }
        }
        return 250L;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
