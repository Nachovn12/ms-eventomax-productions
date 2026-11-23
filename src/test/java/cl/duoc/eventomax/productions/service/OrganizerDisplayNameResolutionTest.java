package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.OrganizerResolutionSource;
import cl.duoc.eventomax.productions.dto.ProductionResponseDTO;
import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.integration.graph.MicrosoftGraphEligibleOrganizerProvider;
import cl.duoc.eventomax.productions.integration.graph.MicrosoftGraphProperties;
import cl.duoc.eventomax.productions.model.OrganizerContact;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.InventoryReservationTaskRepository;
import cl.duoc.eventomax.productions.repository.OrganizerContactRepository;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OrganizerDisplayNameResolutionTest {

    private static final String TENANT = "f191bdc8-7926-4722-9ec0-cba663701940";
    private static final String OTHER_TENANT = "99999999-8888-4777-8666-555555555555";
    private static final String OID_PRODUCTOR_A = "7d300514-1e39-4013-85e5-02efa3fd42df";
    private static final String OID_PRODUCTOR_B = "11111111-2222-4333-8444-555555555555";
    private static final String OID_ADMIN = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String OID_ORGANIZADOR = "e5660fea-f5dd-4974-abb0-47e92f389d63";
    private static final String OID_DISABLED = "22222222-2222-4222-8222-222222222222";
    private static final String OID_MISSING = "33333333-3333-4333-8333-333333333333";

    private MicrosoftGraphProperties properties;
    private MockRestServiceServer server;
    private MicrosoftGraphEligibleOrganizerProvider graphProvider;
    private OrganizerContactRepository contactRepository;
    private OrganizerDisplayNameResolver resolver;
    private ProductionRepository productionRepository;
    private ProductionService productionService;

    @BeforeEach
    void setUp() {
        properties = new MicrosoftGraphProperties();
        properties.setEnabled(true);
        properties.setTenantId(TENANT);
        properties.setClientId("client-id-test");
        properties.setClientSecret("test-secret-not-real");
        // Intentionally leave servicePrincipalId and organizerAppRoleId empty to prove
        // user displayName resolution only requires User.Read.All credentials (tenant, clientId, secret).
        properties.setServicePrincipalId("");
        properties.setOrganizerAppRoleId("");
        properties.setTokenBaseUrl("https://login.test");
        properties.setGraphBaseUrl("https://graph.test/v1.0");
        properties.setCacheTtl(Duration.ofMinutes(5));

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        graphProvider = new MicrosoftGraphEligibleOrganizerProvider(properties, builder.build());

        contactRepository = mock(OrganizerContactRepository.class);
        resolver = new OrganizerDisplayNameResolver(graphProvider, contactRepository);

        productionRepository = mock(ProductionRepository.class);
        productionService = new ProductionService(
                productionRepository,
                mock(ApplicationEventPublisher.class),
                mock(CatalogClient.class),
                mock(OutboxEventRepository.class),
                mock(InventoryReservationTaskRepository.class),
                mock(InventoryReservationTaskService.class),
                mock(ProductionStateCoordinator.class),
                null,
                null,
                null,
                resolver
        );
    }

    private void stubToken() {
        server.expect(requestTo("https://login.test/" + TENANT + "/oauth2/v2.0/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"mock-graph-token\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
    }

    private Production sampleProduction(Long id, String organizerId, String name) {
        Production p = new Production();
        p.setId(id);
        p.setOrganizerId(organizerId);
        p.setName(name);
        p.setLocation("Centro Eventos");
        p.setScheduledAt(LocalDateTime.of(2030, 11, 24, 19, 0));
        p.setStatus(ProductionStatus.SOLICITADO);
        p.setCreatedAt(LocalDateTime.of(2026, 11, 22, 10, 0));
        p.setUpdatedAt(LocalDateTime.of(2026, 11, 22, 10, 0));
        return p;
    }

    @Test
    void singleProduction10_resolvesRealEntraDisplayNameForProducerWithoutModifyingOrganizerId() {
        String prod10Owner = TENANT + ":" + OID_PRODUCTOR_A;
        when(productionRepository.findById(10L))
                .thenReturn(Optional.of(sampleProduction(10L, prod10Owner, "Produccion #10")));

        stubToken();
        server.expect(requestTo("https://graph.test/v1.0/users/" + OID_PRODUCTOR_A + "?$select=id,displayName,accountEnabled"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"%s","displayName":"Ignacio Valeria","accountEnabled":true}
                        """.formatted(OID_PRODUCTOR_A), MediaType.APPLICATION_JSON));

        Optional<ProductionResponseDTO> dtoOpt = productionService.getProductionById(10L);

        assertThat(dtoOpt).isPresent();
        ProductionResponseDTO dto = dtoOpt.get();
        assertThat(dto.organizerId()).isEqualTo(prod10Owner);
        assertThat(dto.organizerDisplayName()).isEqualTo("Ignacio Valeria");
        assertThat(dto.organizerResolutionSource()).isEqualTo(OrganizerResolutionSource.ENTRA_GRAPH);
        verify(contactRepository, never()).findByOrganizerIdInAndActiveTrue(anyCollection());
        server.verify();
    }

    @Test
    void multiUserBatch_resolvesDifferentRolesAndDeduplicatesWithTtlCache() {
        String idA = TENANT + ":" + OID_PRODUCTOR_A;
        String idB = TENANT + ":" + OID_PRODUCTOR_B;
        String idAdmin = TENANT + ":" + OID_ADMIN;
        String idOrg = TENANT + ":" + OID_ORGANIZADOR;

        List<Production> list = List.of(
                sampleProduction(10L, idA, "Evento Productor A #1"),
                sampleProduction(11L, idA, "Evento Productor A #2"),
                sampleProduction(12L, idB, "Evento Productor B"),
                sampleProduction(13L, idAdmin, "Evento Admin"),
                sampleProduction(14L, idOrg, "Evento Organizador")
        );
        when(productionRepository.findAll()).thenReturn(list);

        stubToken();
        // Expect exactly ONE $batch HTTP call for all 4 distinct Entra users (no N+1)
        server.expect(requestTo("https://graph.test/v1.0/$batch"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "responses": [
                            {"id":"0","status":200,"body":{"id":"%s","displayName":"Ignacio Valeria","accountEnabled":true}},
                            {"id":"1","status":200,"body":{"id":"%s","displayName":"Valentina Rojas","accountEnabled":true}},
                            {"id":"2","status":200,"body":{"id":"%s","displayName":"Administrador EventoMax","accountEnabled":true}},
                            {"id":"3","status":200,"body":{"id":"%s","displayName":"Benjamin Espinoza","accountEnabled":true}}
                          ]
                        }
                        """.formatted(OID_PRODUCTOR_A, OID_PRODUCTOR_B, OID_ADMIN, OID_ORGANIZADOR),
                        MediaType.APPLICATION_JSON));

        List<ProductionResponseDTO> firstRead = productionService.getAllProductions();
        assertThat(firstRead).hasSize(5);
        assertThat(firstRead.get(0).organizerDisplayName()).isEqualTo("Ignacio Valeria");
        assertThat(firstRead.get(1).organizerDisplayName()).isEqualTo("Ignacio Valeria");
        assertThat(firstRead.get(2).organizerDisplayName()).isEqualTo("Valentina Rojas");
        assertThat(firstRead.get(3).organizerDisplayName()).isEqualTo("Administrador EventoMax");
        assertThat(firstRead.get(4).organizerDisplayName()).isEqualTo("Benjamin Espinoza");
        assertThat(firstRead).allMatch(dto -> dto.organizerResolutionSource() == OrganizerResolutionSource.ENTRA_GRAPH);

        // Second read must hit TTL cache with ZERO additional HTTP calls to Graph
        List<ProductionResponseDTO> cachedRead = productionService.getAllProductions();
        assertThat(cachedRead.get(2).organizerDisplayName()).isEqualTo("Valentina Rojas");
        server.verify();
    }

    @Test
    void foreignTenantUuid_neverCallsGraph_andReturnsUnresolved() {
        String foreignOwner = OTHER_TENANT + ":" + OID_PRODUCTOR_A;
        when(productionRepository.findById(20L))
                .thenReturn(Optional.of(sampleProduction(20L, foreignOwner, "Evento Otro Tenant")));

        Optional<ProductionResponseDTO> dtoOpt = productionService.getProductionById(20L);

        assertThat(dtoOpt).isPresent();
        assertThat(dtoOpt.get().organizerId()).isEqualTo(foreignOwner);
        assertThat(dtoOpt.get().organizerDisplayName()).isNull();
        assertThat(dtoOpt.get().organizerResolutionSource()).isEqualTo(OrganizerResolutionSource.UNRESOLVED);
        server.verify(); // 0 HTTP requests expected
    }

    @Test
    void missingAndDisabledUsers_returnUnresolvedAndCacheNegativeResult() {
        String disabledId = TENANT + ":" + OID_DISABLED;
        String missingId = TENANT + ":" + OID_MISSING;

        stubToken();
        server.expect(requestTo("https://graph.test/v1.0/$batch"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "responses": [
                            {"id":"0","status":200,"body":{"id":"%s","displayName":"Usuario Inactivo","accountEnabled":false}},
                            {"id":"1","status":404,"body":{"error":{"code":"Request_ResourceNotFound"}}}
                          ]
                        }
                        """.formatted(OID_DISABLED), MediaType.APPLICATION_JSON));

        Map<String, OrganizerDisplayNameResolver.ResolvedOrganizer> first =
                resolver.resolveAll(List.of(disabledId, missingId));
        assertThat(first.get(disabledId).displayName()).isNull();
        assertThat(first.get(disabledId).source()).isEqualTo(OrganizerResolutionSource.UNRESOLVED);
        assertThat(first.get(missingId).displayName()).isNull();
        assertThat(first.get(missingId).source()).isEqualTo(OrganizerResolutionSource.UNRESOLVED);

        // Repeat lookup hits negative cache without calling Graph again
        Map<String, OrganizerDisplayNameResolver.ResolvedOrganizer> second =
                resolver.resolveAll(List.of(disabledId, missingId));
        assertThat(second.get(disabledId).source()).isEqualTo(OrganizerResolutionSource.UNRESOLVED);
        assertThat(second.get(missingId).source()).isEqualTo(OrganizerResolutionSource.UNRESOLVED);
        server.verify();
    }

    @Test
    void graphFailures_401_403_429_5xx_andTimeout_degradeGracefullyToUnresolved() {
        String idA = TENANT + ":" + OID_PRODUCTOR_A;
        when(productionRepository.findById(30L))
                .thenReturn(Optional.of(sampleProduction(30L, idA, "Evento Resiliente")));

        // 1. HTTP 401 -> degrades cleanly and invalidates token cache
        stubToken();
        server.expect(requestTo("https://graph.test/v1.0/users/" + OID_PRODUCTOR_A + "?$select=id,displayName,accountEnabled"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        ProductionResponseDTO res401 = productionService.getProductionById(30L).orElseThrow();
        assertThat(res401.organizerDisplayName()).isNull();
        assertThat(res401.organizerResolutionSource()).isEqualTo(OrganizerResolutionSource.UNRESOLVED);
        server.verify();
        server.reset();

        // 2. HTTP 403 -> requests fresh token (since 401 invalidated cache), degrades cleanly
        stubToken();
        server.expect(requestTo("https://graph.test/v1.0/users/" + OID_PRODUCTOR_A + "?$select=id,displayName,accountEnabled"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        ProductionResponseDTO res403 = productionService.getProductionById(30L).orElseThrow();
        assertThat(res403.organizerDisplayName()).isNull();
        assertThat(res403.organizerResolutionSource()).isEqualTo(OrganizerResolutionSource.UNRESOLVED);
        server.verify();
        server.reset();

        // 3. HTTP 500 -> requests fresh token (since 403 invalidated cache), degrades cleanly
        stubToken();
        server.expect(requestTo("https://graph.test/v1.0/users/" + OID_PRODUCTOR_A + "?$select=id,displayName,accountEnabled"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        ProductionResponseDTO res500 = productionService.getProductionById(30L).orElseThrow();
        assertThat(res500.organizerDisplayName()).isNull();
        assertThat(res500.organizerResolutionSource()).isEqualTo(OrganizerResolutionSource.UNRESOLVED);
        server.verify();
        server.reset();

        // 4. Timeout -> reuses valid cached token, degrades cleanly
        server.expect(requestTo("https://graph.test/v1.0/users/" + OID_PRODUCTOR_A + "?$select=id,displayName,accountEnabled"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new ResourceAccessException("Read timed out", new SocketTimeoutException("timeout"));
                });

        ProductionResponseDTO resTimeout = productionService.getProductionById(30L).orElseThrow();
        assertThat(resTimeout.organizerDisplayName()).isNull();
        assertThat(resTimeout.organizerResolutionSource()).isEqualTo(OrganizerResolutionSource.UNRESOLVED);
        server.verify();
        server.reset();

        // 5. HTTP 429 with Retry-After -> single retry succeeds
        HttpHeaders retryHeaders = new HttpHeaders();
        retryHeaders.set("Retry-After", "0");
        server.expect(requestTo("https://graph.test/v1.0/users/" + OID_PRODUCTOR_A + "?$select=id,displayName,accountEnabled"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryHeaders));
        server.expect(requestTo("https://graph.test/v1.0/users/" + OID_PRODUCTOR_A + "?$select=id,displayName,accountEnabled"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"%s","displayName":"Ignacio Valeria","accountEnabled":true}
                        """.formatted(OID_PRODUCTOR_A), MediaType.APPLICATION_JSON));

        ProductionResponseDTO res429Recovered = productionService.getProductionById(30L).orElseThrow();
        assertThat(res429Recovered.organizerDisplayName()).isEqualTo("Ignacio Valeria");
        assertThat(res429Recovered.organizerResolutionSource()).isEqualTo(OrganizerResolutionSource.ENTRA_GRAPH);

        server.verify();
    }

    @Test
    void externalClients_resolvedFromOrganizerContactsWithoutCallingGraph() {
        OrganizerContact ext1 = new OrganizerContact("ext-festival-costero", "contacto@costero.cl", "Festival Costero SpA", null, "actor");
        OrganizerContact ext2NoName = new OrganizerContact("ext-sin-nombre", "anon@externo.cl", "   ", null, "actor");

        when(contactRepository.findByOrganizerIdInAndActiveTrue(anyCollection()))
                .thenReturn(List.of(ext1, ext2NoName));
        when(productionRepository.findAll()).thenReturn(List.of(
                sampleProduction(40L, "ext-festival-costero", "Gala Costera"),
                sampleProduction(41L, "ext-sin-nombre", "Evento Externo Sin Nombre")
        ));

        List<ProductionResponseDTO> list = productionService.getAllProductions();

        assertThat(list).hasSize(2);
        assertThat(list.get(0).organizerId()).isEqualTo("ext-festival-costero");
        assertThat(list.get(0).organizerDisplayName()).isEqualTo("Festival Costero SpA");
        assertThat(list.get(0).organizerResolutionSource()).isEqualTo(OrganizerResolutionSource.EXTERNAL_CONTACT);

        assertThat(list.get(1).organizerId()).isEqualTo("ext-sin-nombre");
        assertThat(list.get(1).organizerDisplayName()).isNull();
        assertThat(list.get(1).organizerResolutionSource()).isEqualTo(OrganizerResolutionSource.UNRESOLVED);

        server.verify(); // ZERO calls to Microsoft Graph
    }

    @Test
    void organizerRoleIsolation_onlyResolvesAuthorizedOrganizers() {
        String ownId = TENANT + ":" + OID_ORGANIZADOR;
        when(productionRepository.findByOrganizerIdIn(List.of(ownId)))
                .thenReturn(List.of(sampleProduction(50L, ownId, "Evento Propio Organizador")));

        stubToken();
        server.expect(requestTo("https://graph.test/v1.0/users/" + OID_ORGANIZADOR + "?$select=id,displayName,accountEnabled"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"%s","displayName":"Benjamin Espinoza","accountEnabled":true}
                        """.formatted(OID_ORGANIZADOR), MediaType.APPLICATION_JSON));

        List<ProductionResponseDTO> scoped = productionService.getAllProductionsForOrganizers(List.of(ownId));
        assertThat(scoped).hasSize(1);
        assertThat(scoped.get(0).organizerDisplayName()).isEqualTo("Benjamin Espinoza");
        assertThat(scoped.get(0).organizerResolutionSource()).isEqualTo(OrganizerResolutionSource.ENTRA_GRAPH);
        server.verify();
    }

    @Test
    void disabledGraphConfiguration_keepsReads200WithUnresolved() {
        properties.setEnabled(false);
        String idA = TENANT + ":" + OID_PRODUCTOR_A;
        when(productionRepository.findById(60L))
                .thenReturn(Optional.of(sampleProduction(60L, idA, "Evento Graph Deshabilitado")));

        ProductionResponseDTO dto = productionService.getProductionById(60L).orElseThrow();
        assertThat(dto.organizerId()).isEqualTo(idA);
        assertThat(dto.organizerDisplayName()).isNull();
        assertThat(dto.organizerResolutionSource()).isEqualTo(OrganizerResolutionSource.UNRESOLVED);
        server.verify();
    }
}
