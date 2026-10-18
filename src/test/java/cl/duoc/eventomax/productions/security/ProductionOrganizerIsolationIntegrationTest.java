package cl.duoc.eventomax.productions.security;

import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductionOrganizerIsolationIntegrationTest {

    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"));

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("spring.datasource.driver-class-name", DB::getDriverClassName);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ProductionRepository productionRepository;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    CatalogClient catalogClient;

    private static final String TENANT_ID = "tenant-duoc-ep2";
    private static final String ORG_A_OID = "11111111-aaaa-bbbb-cccc-111111111111";
    private static final String ORG_A_CANONICAL = TENANT_ID + ":" + ORG_A_OID;

    private static final String ORG_B_OID = "22222222-aaaa-bbbb-cccc-222222222222";
    private static final String ORG_B_CANONICAL = TENANT_ID + ":" + ORG_B_OID;

    private JwtRequestPostProcessor tokenOrganizerA() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("SCOPE_access_as_user"), new SimpleGrantedAuthority("ROLE_Organizador"))
                .jwt(j -> j
                        .claim("oid", ORG_A_OID)
                        .claim("tid", TENANT_ID)
                        .claim("scp", "access_as_user")
                        .claim("roles", List.of("Organizador"))
                        .subject(ORG_A_OID));
    }

    private JwtRequestPostProcessor tokenOrganizerB() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("SCOPE_access_as_user"), new SimpleGrantedAuthority("ROLE_Organizador"))
                .jwt(j -> j
                        .claim("oid", ORG_B_OID)
                        .claim("tid", TENANT_ID)
                        .claim("scp", "access_as_user")
                        .claim("roles", List.of("Organizador"))
                        .subject(ORG_B_OID));
    }

    private JwtRequestPostProcessor tokenAdmin() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("SCOPE_access_as_user"), new SimpleGrantedAuthority("ROLE_Admin"))
                .jwt(j -> j
                        .claim("oid", "admin-oid")
                        .claim("scp", "access_as_user")
                        .claim("roles", List.of("Admin"))
                        .subject("admin-user"));
    }

    private JwtRequestPostProcessor tokenProductor() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("SCOPE_access_as_user"), new SimpleGrantedAuthority("ROLE_Productor"))
                .jwt(j -> j
                        .claim("oid", "productor-oid")
                        .claim("scp", "access_as_user")
                        .claim("roles", List.of("Productor"))
                        .subject("productor-user"));
    }

    @BeforeEach
    void cleanDatabase() {
        outboxEventRepository.deleteAll();
        productionRepository.deleteAll();
    }

    @Test
    @DisplayName("1 y 4. Organizador A crea produccion propia y puede consultar su detalle por ID")
    void organizerA_createsAndCanReadOwnProduction() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "organizerId", ORG_A_CANONICAL,
                "name", "Concierto Primavera A",
                "scheduledAt", "2030-05-10T20:00:00",
                "location", "Teatro Caupolican"
        ));

        String responseJson = mockMvc.perform(post("/api/productions")
                        .with(tokenOrganizerA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizerId", is(ORG_A_CANONICAL)))
                .andExpect(jsonPath("$.name", is("Concierto Primavera A")))
                .andReturn().getResponse().getContentAsString();

        Long id = objectMapper.readTree(responseJson).get("id").asLong();

        mockMvc.perform(get("/api/productions/{id}", id)
                        .with(tokenOrganizerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id.intValue())))
                .andExpect(jsonPath("$.organizerId", is(ORG_A_CANONICAL)))
                .andExpect(jsonPath("$.name", is("Concierto Primavera A")));
    }

    @Test
    @DisplayName("2. Organizador B no puede consultar el detalle de la produccion de A (403 Forbidden)")
    void organizerB_cannotReadDetailOfOrganizerA() throws Exception {
        Production prodA = new Production(ORG_A_CANONICAL, "Gala de A", LocalDateTime.now().plusDays(5), "Arena Santiago");
        prodA = productionRepository.save(prodA);

        mockMvc.perform(get("/api/productions/{id}", prodA.getId())
                        .with(tokenOrganizerB()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("3. Organizador B no encuentra la produccion de A en el listado global")
    void organizerB_doesNotSeeProductionOfOrganizerAInList() throws Exception {
        Production prodA = new Production(ORG_A_CANONICAL, "Gala de A", LocalDateTime.now().plusDays(5), "Arena Santiago");
        productionRepository.save(prodA);

        Production prodB = new Production(ORG_B_CANONICAL, "Festival de B", LocalDateTime.now().plusDays(7), "Espacio Riesco");
        productionRepository.save(prodB);

        mockMvc.perform(get("/api/productions")
                        .with(tokenOrganizerB()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].organizerId", is(ORG_B_CANONICAL)))
                .andExpect(jsonPath("$[0].name", is("Festival de B")));
    }

    @Test
    @DisplayName("5. Organizador A intenta suplantar a B en creacion y el backend lo rechaza (403)")
    void organizerA_cannotImpersonateOrganizerBInCreate() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "organizerId", ORG_B_CANONICAL,
                "name", "Intento Suplantacion",
                "scheduledAt", "2030-08-15T18:00:00",
                "location", "Club Hipico"
        ));

        mockMvc.perform(post("/api/productions")
                        .with(tokenOrganizerA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("6. Admin y Productor conservan accesos autorizados y visibilidad global")
    void adminAndProductor_haveGlobalAccess() throws Exception {
        Production prodA = new Production(ORG_A_CANONICAL, "Evento A", LocalDateTime.now().plusDays(2), "Teatro Oriente");
        prodA = productionRepository.save(prodA);

        Production prodB = new Production(ORG_B_CANONICAL, "Evento B", LocalDateTime.now().plusDays(3), "Teatro Municipal");
        prodB = productionRepository.save(prodB);

        // Admin lista ambas
        mockMvc.perform(get("/api/productions")
                        .with(tokenAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // Admin consulta detalle de A
        mockMvc.perform(get("/api/productions/{id}", prodA.getId())
                        .with(tokenAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizerId", is(ORG_A_CANONICAL)));

        // Productor lista ambas
        mockMvc.perform(get("/api/productions")
                        .with(tokenProductor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // Productor consulta detalle de B
        mockMvc.perform(get("/api/productions/{id}", prodB.getId())
                        .with(tokenProductor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizerId", is(ORG_B_CANONICAL)));
    }

    @Test
    @DisplayName("7. Filtros combinados por estado y fechas funcionan respetando el aislamiento por propietario")
    void combinedFilters_respectOrganizerIsolation() throws Exception {
        LocalDateTime date1 = LocalDateTime.of(2030, 1, 10, 12, 0);
        LocalDateTime date2 = LocalDateTime.of(2030, 2, 10, 12, 0);

        Production prodA1 = new Production(ORG_A_CANONICAL, "A1 Solicitado", date1, "Loc 1");
        prodA1.setStatus(ProductionStatus.SOLICITADO);
        productionRepository.save(prodA1);

        Production prodA2 = new Production(ORG_A_CANONICAL, "A2 Confirmado", date2, "Loc 2");
        prodA2.setStatus(ProductionStatus.CONFIRMADO);
        productionRepository.save(prodA2);

        // Producción de B en la misma fecha y estado
        Production prodB1 = new Production(ORG_B_CANONICAL, "B1 Solicitado", date1, "Loc 1");
        prodB1.setStatus(ProductionStatus.SOLICITADO);
        productionRepository.save(prodB1);

        // Organizador A filtra por estado SOLICITADO
        mockMvc.perform(get("/api/productions")
                        .param("status", "SOLICITADO")
                        .with(tokenOrganizerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("A1 Solicitado")));

        // Organizador A filtra por rango temporal que solo cubre fecha 2
        mockMvc.perform(get("/api/productions")
                        .param("from", "2030-02-01T00:00:00")
                        .param("to", "2030-02-28T23:59:59")
                        .with(tokenOrganizerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("A2 Confirmado")));
    }

    @Test
    @DisplayName("8. Tokens con claims de propietario ausentes o invalidos aplican fail-closed")
    void missingOrInvalidOwnerClaims_failsClosed() throws Exception {
        // Token sin oid ni sub
        var tokenWithoutIdentity = jwt().jwt(j -> j
                .claim("scp", "access_as_user")
                .claim("roles", List.of("Organizador")));

        mockMvc.perform(get("/api/productions")
                        .with(tokenWithoutIdentity))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("9. Producciones heredadas con identificadores incompatibles no se exponen a organizadores")
    void legacyProductions_withArbitraryIds_notExposedToOrganizers() throws Exception {
        Production legacy = new Production("legacy-unknown-organizer", "Evento Historico EP1", LocalDateTime.now().plusDays(1), "Estadio Chile");
        legacy = productionRepository.save(legacy);

        // Organizador A lista producciones y no ve la heredada
        mockMvc.perform(get("/api/productions")
                        .with(tokenOrganizerA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // Organizador A intenta consultar por ID la heredada y recibe 403
        mockMvc.perform(get("/api/productions/{id}", legacy.getId())
                        .with(tokenOrganizerA()))
                .andExpect(status().isForbidden());

        // Admin si puede consultar la produccion heredada
        mockMvc.perform(get("/api/productions/{id}", legacy.getId())
                        .with(tokenAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizerId", is("legacy-unknown-organizer")));
    }

    @Test
    @DisplayName("10. Organizador no puede actualizar el estado de una producción de otro organizador (403 Forbidden)")
    void organizer_cannotUpdateStatus_ofAnotherOrganizersProduction() throws Exception {
        Production prodB = new Production(ORG_B_CANONICAL, "Concierto B", LocalDateTime.now().plusDays(5), "Teatro Caupolicán");
        prodB = productionRepository.save(prodB);

        String updateJson = objectMapper.writeValueAsString(Map.of("status", "CANCELADO"));

        // Organizador A intenta cancelar la producción de Organizador B -> 403 Forbidden
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/productions/{id}/status", prodB.getId())
                        .with(tokenOrganizerA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isForbidden());

        // Verificar que la producción sigue en SOLICITADO
        Production current = productionRepository.findById(prodB.getId()).orElseThrow();
        assertEquals(ProductionStatus.SOLICITADO, current.getStatus());
    }

    @Test
    @DisplayName("11. Administrador puede actualizar el estado de la producción de cualquier organizador")
    void admin_canUpdateStatus_ofAnyOrganizersProduction() throws Exception {
        Production prodB = new Production(ORG_B_CANONICAL, "Concierto B", LocalDateTime.now().plusDays(5), "Teatro Caupolicán");
        prodB = productionRepository.save(prodB);

        String updateJson = objectMapper.writeValueAsString(Map.of("status", "CANCELADO"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/productions/{id}/status", prodB.getId())
                        .with(tokenAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELADO")));

        Production current = productionRepository.findById(prodB.getId()).orElseThrow();
        assertEquals(ProductionStatus.CANCELADO, current.getStatus());
    }
}

