package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.OrganizerContactRequestDTO;
import cl.duoc.eventomax.productions.dto.OrganizerContactResponseDTO;
import cl.duoc.eventomax.productions.model.OrganizerContact;
import cl.duoc.eventomax.productions.repository.OrganizerContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrganizerContactServiceTest {

    @Mock
    private OrganizerContactRepository contactRepository;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private OrganizerContactService service;

    private Jwt adminJwt;
    private Jwt organizerJwt;

    @BeforeEach
    void setUp() {
        adminJwt = Jwt.withTokenValue("mock-admin-token")
                .header("alg", "none")
                .claim("sub", "admin-sub")
                .claim("roles", List.of("Admin"))
                .build();

        organizerJwt = Jwt.withTokenValue("mock-organizer-token")
                .header("alg", "none")
                .claim("sub", "org-ep2-demo-001")
                .claim("roles", List.of("Organizador"))
                .build();
    }

    @Test
    @DisplayName("getContact: Admin puede consultar cualquier contacto")
    void getContact_asAdmin_returnsContact() {
        when(authentication.getAuthorities()).thenReturn((java.util.Collection) List.of(new SimpleGrantedAuthority("ROLE_Admin")));
        OrganizerContact contact = new OrganizerContact("org-123", "org@eventomax.cl", "Nombre", "+569111", "admin-sub");
        when(contactRepository.findByOrganizerIdAndActiveTrue("org-123")).thenReturn(Optional.of(contact));

        OrganizerContactResponseDTO response = service.getContact("org-123", authentication, adminJwt);

        assertNotNull(response);
        assertEquals("org-123", response.organizerId());
        assertEquals("org@eventomax.cl", response.email());
    }

    @Test
    @DisplayName("getContact: Organizador propio puede consultar su contacto (coincidencia directa)")
    void getContact_asOwner_returnsContact() {
        when(authentication.getAuthorities()).thenReturn((java.util.Collection) List.of(new SimpleGrantedAuthority("ROLE_Organizador")));
        OrganizerContact contact = new OrganizerContact("org-ep2-demo-001", "org@eventomax.cl", "Nombre", "+569111", "org-ep2-demo-001");
        when(contactRepository.findByOrganizerIdAndActiveTrue("org-ep2-demo-001")).thenReturn(Optional.of(contact));

        OrganizerContactResponseDTO response = service.getContact("org-ep2-demo-001", authentication, organizerJwt);

        assertNotNull(response);
        assertEquals("org-ep2-demo-001", response.organizerId());
    }

    @Test
    @DisplayName("getContact: Usuario Entra ID autenticado con OID distinto a organizerId accede legítimamente mediante mapeo user_oid")
    void getContact_asEntraIdUser_mappedByUserOid_returnsContact() {
        String entraIdOid = "d762a6e9-9134-4a4b-97e3-057bfd9d883b";
        Jwt entraJwt = Jwt.withTokenValue("entra-token")
                .header("alg", "none")
                .claim("oid", entraIdOid)
                .claim("tid", "tenant-ep2-duoc")
                .claim("roles", List.of("Organizador"))
                .subject("entra-subject-ignored")
                .build();

        when(authentication.getAuthorities()).thenReturn((java.util.Collection) List.of(new SimpleGrantedAuthority("ROLE_Organizador")));
        OrganizerContact contact = new OrganizerContact("org-ep2-demo-001", "org@eventomax.cl", "Nombre", "+569111", entraIdOid, "admin");
        when(contactRepository.findByOrganizerIdAndActiveTrue("org-ep2-demo-001")).thenReturn(Optional.of(contact));

        OrganizerContactResponseDTO response = service.getContact("org-ep2-demo-001", authentication, entraJwt);

        assertNotNull(response);
        assertEquals("org-ep2-demo-001", response.organizerId());
        assertEquals(entraIdOid, response.userOid());
    }

    @Test
    @DisplayName("getContact: Usuario Entra ID autenticado accede legítimamente mediante coincidencia de preferred_username / email")
    void getContact_asEntraIdUser_mappedByPreferredUsername_returnsContact() {
        String userEmail = "organizador.cert@eventomax.cl";
        String entraOid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        Jwt entraJwt = Jwt.withTokenValue("entra-token-email")
                .header("alg", "none")
                .claim("oid", entraOid)
                .claim("preferred_username", userEmail)
                .claim("roles", List.of("Organizador"))
                .subject(entraOid)
                .build();

        when(authentication.getAuthorities()).thenReturn((java.util.Collection) List.of(new SimpleGrantedAuthority("ROLE_Organizador")));
        OrganizerContact contact = new OrganizerContact("org-ep2-demo-001", userEmail, "Contacto Cert", "+569111", "admin");
        when(contactRepository.findByOrganizerIdAndActiveTrue("org-ep2-demo-001")).thenReturn(Optional.of(contact));

        OrganizerContactResponseDTO response = service.getContact("org-ep2-demo-001", authentication, entraJwt);

        assertNotNull(response);
        assertEquals("org-ep2-demo-001", response.organizerId());
        assertEquals(userEmail, response.email());
    }

    @Test
    @DisplayName("getContact: Organizador ajeno no vinculado por OID ni email es bloqueado con AccessDeniedException (IDOR fail-closed)")
    void getContact_asUnlinkedOrganizer_throwsAccessDenied() {
        String attackerOid = "attacker-oid-666";
        String attackerEmail = "attacker@other-domain.cl";
        Jwt attackerJwt = Jwt.withTokenValue("attacker-token")
                .header("alg", "none")
                .claim("oid", attackerOid)
                .claim("preferred_username", attackerEmail)
                .claim("roles", List.of("Organizador"))
                .subject(attackerOid)
                .build();

        when(authentication.getAuthorities()).thenReturn((java.util.Collection) List.of(new SimpleGrantedAuthority("ROLE_Organizador")));
        OrganizerContact legitContact = new OrganizerContact("org-ep2-demo-001", "legit@domain.cl", "Legit", "+569111", "legit-oid", "admin");
        when(contactRepository.findByOrganizerIdAndActiveTrue("org-ep2-demo-001")).thenReturn(Optional.of(legitContact));

        assertThrows(AccessDeniedException.class, () ->
                service.getContact("org-ep2-demo-001", authentication, attackerJwt)
        );
    }

    @Test
    @DisplayName("getContact: Contacto inexistente lanza ResourceNotFoundException")
    void getContact_notFound_throwsResourceNotFound() {
        when(authentication.getAuthorities()).thenReturn((java.util.Collection) List.of(new SimpleGrantedAuthority("ROLE_Admin")));
        when(contactRepository.findByOrganizerIdAndActiveTrue("org-404")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.getContact("org-404", authentication, adminJwt)
        );
    }

    @Test
    @DisplayName("upsertContact: Creación exitosa de nuevo contacto")
    void upsertContact_createsNew() {
        when(authentication.getAuthorities()).thenReturn((java.util.Collection) List.of(new SimpleGrantedAuthority("ROLE_Admin")));
        when(contactRepository.findByOrganizerId("org-nuevo")).thenReturn(Optional.empty());

        OrganizerContact saved = new OrganizerContact("org-nuevo", "nuevo@eventomax.cl", "Contacto", "+569222", "admin-sub");
        when(contactRepository.save(any(OrganizerContact.class))).thenReturn(saved);

        OrganizerContactRequestDTO request = new OrganizerContactRequestDTO("nuevo@eventomax.cl", "Contacto", "+569222");
        OrganizerContactResponseDTO response = service.upsertContact("org-nuevo", request, authentication, adminJwt);

        assertNotNull(response);
        assertEquals("nuevo@eventomax.cl", response.email());
        verify(contactRepository, times(1)).save(any(OrganizerContact.class));
    }

    @Test
    @DisplayName("upsertContact: Actualización de contacto existente")
    void upsertContact_updatesExisting() {
        when(authentication.getAuthorities()).thenReturn((java.util.Collection) List.of(new SimpleGrantedAuthority("ROLE_Admin")));
        OrganizerContact existing = new OrganizerContact("org-1", "viejo@eventomax.cl", "Viejo", "+569000", "actor1");
        when(contactRepository.findByOrganizerId("org-1")).thenReturn(Optional.of(existing));
        when(contactRepository.save(any(OrganizerContact.class))).thenAnswer(i -> i.getArgument(0));

        OrganizerContactRequestDTO request = new OrganizerContactRequestDTO("nuevo@eventomax.cl", "Actualizado", "+569333");
        OrganizerContactResponseDTO response = service.upsertContact("org-1", request, authentication, adminJwt);

        assertNotNull(response);
        assertEquals("nuevo@eventomax.cl", response.email());
        assertEquals("Actualizado", response.contactName());
    }

    @Test
    @DisplayName("getVerifiedRecipientEmail: Retorna email verificado si activo, o empty si no existe")
    void getVerifiedRecipientEmail_returnsActiveEmail() {
        OrganizerContact contact = new OrganizerContact("org-1", "contacto@real.cl", "Nombre", "+569", "system");
        when(contactRepository.findByOrganizerIdAndActiveTrue("org-1")).thenReturn(Optional.of(contact));
        when(contactRepository.findByOrganizerIdAndActiveTrue("org-none")).thenReturn(Optional.empty());

        assertTrue(service.getVerifiedRecipientEmail("org-1").isPresent());
        assertEquals("contacto@real.cl", service.getVerifiedRecipientEmail("org-1").get());
        assertTrue(service.getVerifiedRecipientEmail("org-none").isEmpty());
        assertTrue(service.getVerifiedRecipientEmail(null).isEmpty());
        assertTrue(service.getVerifiedRecipientEmail("  ").isEmpty());
    }

    @Test
    @DisplayName("isAuthorizedForOrganizer: Admin y Productor retornan siempre true")
    void isAuthorizedForOrganizer_privilegedRoles_returnsTrue() {
        when(authentication.getAuthorities()).thenReturn((java.util.Collection) List.of(new SimpleGrantedAuthority("ROLE_Admin")));
        assertTrue(service.isAuthorizedForOrganizer("cualquier-org", authentication, adminJwt));

        when(authentication.getAuthorities()).thenReturn((java.util.Collection) List.of(new SimpleGrantedAuthority("ROLE_Productor")));
        assertTrue(service.isAuthorizedForOrganizer("cualquier-org", authentication, adminJwt));
    }

    @Test
    @DisplayName("resolveAuthorizedOrganizerIds: Retorna lista con IDs autorizados por OID y email, más ownerId del token")
    void resolveAuthorizedOrganizerIds_returnsMappedAndOwnerIds() {
        String oid = "entra-oid-555";
        String email = "org@eventomax.cl";
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("oid", oid)
                .claim("preferred_username", email)
                .claim("roles", List.of("Organizador"))
                .subject(oid)
                .build();

        OrganizerContact contactOid = new OrganizerContact("org-ep2-demo-001", "other@eventomax.cl", "Name", "+569", oid, "admin");
        OrganizerContact contactEmail = new OrganizerContact("org-ep2-demo-002", email, "Name 2", "+569", "other-oid", "admin");

        when(contactRepository.findByActiveTrueAndUserOid(oid)).thenReturn(List.of(contactOid));
        when(contactRepository.findByActiveTrueAndEmailIgnoreCase(email)).thenReturn(List.of(contactEmail));

        List<String> result = service.resolveAuthorizedOrganizerIds(jwt);

        assertTrue(result.contains("org-ep2-demo-001"));
        assertTrue(result.contains("org-ep2-demo-002"));
        assertTrue(result.contains(oid));
    }
}

