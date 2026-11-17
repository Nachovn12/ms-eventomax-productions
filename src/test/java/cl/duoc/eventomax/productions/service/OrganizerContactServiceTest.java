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

    private static final String TID = "33333333-3333-4333-8333-333333333333";
    private static final String OID = "11111111-1111-4111-8111-111111111111";
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
                .claim("sub", "organizer-sub")
                .claim("tid", TID)
                .claim("oid", OID)
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
        OrganizerContact contact = new OrganizerContact("org-ep2-demo-001", "org@eventomax.cl", "Nombre", "+569111", OID, "admin");
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
                .claim("tid", TID)
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
    @DisplayName("getContact: El correo coincidente no prueba propiedad")
    void getContact_asEntraIdUser_mappedOnlyByEmail_isDenied() {
        String userEmail = "organizador.cert@eventomax.cl";
        String entraOid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        Jwt entraJwt = Jwt.withTokenValue("entra-token-email")
                .header("alg", "none")
                .claim("oid", entraOid)
                .claim("tid", TID)
                .claim("preferred_username", userEmail)
                .claim("roles", List.of("Organizador"))
                .subject(entraOid)
                .build();

        when(authentication.getAuthorities()).thenReturn((java.util.Collection) List.of(new SimpleGrantedAuthority("ROLE_Organizador")));
        OrganizerContact contact = new OrganizerContact("org-ep2-demo-001", userEmail, "Contacto Cert", "+569111", "admin");
        when(contactRepository.findByOrganizerIdAndActiveTrue("org-ep2-demo-001")).thenReturn(Optional.of(contact));

        assertThrows(AccessDeniedException.class, () -> service.getContact("org-ep2-demo-001", authentication, entraJwt));
    }

    @Test
    @DisplayName("getContact: Organizador ajeno no vinculado por OID ni email es bloqueado con AccessDeniedException (IDOR fail-closed)")
    void getContact_asUnlinkedOrganizer_throwsAccessDenied() {
        String attackerOid = "22222222-2222-4222-8222-222222222222";
        String attackerEmail = "attacker@other-domain.cl";
        Jwt attackerJwt = Jwt.withTokenValue("attacker-token")
                .header("alg", "none")
                .claim("oid", attackerOid)
                .claim("tid", TID)
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
    @DisplayName("resolveAuthorizedOrganizerIds: Retorna solo IDs por OID estable y propietario canonico")
    void resolveAuthorizedOrganizerIds_returnsMappedAndOwnerIds() {
        String oid = OID;
        String email = "org@eventomax.cl";
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("oid", oid)
                .claim("tid", TID)
                .claim("preferred_username", email)
                .claim("roles", List.of("Organizador"))
                .subject(oid)
                .build();

        OrganizerContact contactOid = new OrganizerContact("org-ep2-demo-001", "other@eventomax.cl", "Name", "+569", oid, "admin");
        OrganizerContact contactEmail = new OrganizerContact("org-ep2-demo-002", email, "Name 2", "+569", "other-oid", "admin");

        when(contactRepository.findByActiveTrueAndUserOid(oid)).thenReturn(List.of(contactOid));

        List<String> result = service.resolveAuthorizedOrganizerIds(jwt);

        assertTrue(result.contains("org-ep2-demo-001"));
        assertFalse(result.contains("org-ep2-demo-002"));
        verify(contactRepository, never()).findByActiveTrueAndEmailIgnoreCase(any());
        assertTrue(result.contains(TID + ":" + oid));
    }

    @Test
    void organizerCannotReassignContactEvenWhenSupplyingAnotherValidUuid() {
        when(authentication.getAuthorities()).thenReturn((java.util.Collection) List.of(new SimpleGrantedAuthority("ROLE_Organizador")));
        when(contactRepository.findByOrganizerIdAndActiveTrue("business-own")).thenReturn(Optional.of(
                new OrganizerContact("business-own", "own@example.test", null, null, OID, "admin")));
        var request = new OrganizerContactRequestDTO("new@example.test", null, null,
                "22222222-2222-4222-8222-222222222222");
        assertThrows(AccessDeniedException.class, () -> service.upsertContact("business-own", request, authentication, organizerJwt));
        verify(contactRepository, never()).save(any());
    }

    @Test
    void organizerEmailEditPreservesExistingOwnerAndDoesNotGrantOtherContacts() {
        when(authentication.getAuthorities()).thenReturn((java.util.Collection) List.of(new SimpleGrantedAuthority("ROLE_Organizador")));
        var existing = new OrganizerContact("business-own", "old@example.test", null, null, OID, "admin");
        when(contactRepository.findByOrganizerIdAndActiveTrue("business-own")).thenReturn(Optional.of(existing));
        when(contactRepository.findByOrganizerId("business-own")).thenReturn(Optional.of(existing));
        when(contactRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        service.upsertContact("business-own", new OrganizerContactRequestDTO("new@example.test", null, null), authentication, organizerJwt);
        assertEquals(OID, existing.getUserOid());
        assertEquals("new@example.test", existing.getEmail());
        verify(contactRepository, never()).findByActiveTrueAndEmailIgnoreCase(any());
    }

    @Test
    void adminCanMaintainBusinessLinkWithoutUsingTheirOwnOid() {
        when(authentication.getAuthorities()).thenReturn((java.util.Collection) List.of(new SimpleGrantedAuthority("ROLE_Admin")));
        when(contactRepository.findByOrganizerId("business")).thenReturn(Optional.empty());
        when(contactRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        var response = service.upsertContact("business", new OrganizerContactRequestDTO("own@example.test", null, null, OID), authentication, adminJwt);
        assertEquals(OID, response.userOid());
    }

    @Test
    void signedEntraIssuerAndTenantMustBeConsistentForOwnership() {
        Jwt mismatched = Jwt.withTokenValue("test").header("alg", "none")
                .issuer("https://login.microsoftonline.com/44444444-4444-4444-8444-444444444444/v2.0")
                .claim("tid", TID).claim("oid", OID).subject("sub").build();
        assertThrows(AccessDeniedException.class, () -> service.resolveAuthorizedOrganizerIds(mismatched));
        verifyNoInteractions(contactRepository);
    }

    @Test
    void malformedOrMissingStableClaimsNeverUseSubjectAsOwnership() {
        for (Jwt token : List.of(
                Jwt.withTokenValue("test").header("alg", "none").subject("legacy-subject").build(),
                Jwt.withTokenValue("test").header("alg", "none").claim("tid", TID).claim("oid", "legacy-owner-0000001").subject("sub").build())) {
            assertThrows(AccessDeniedException.class, () -> service.resolveAuthorizedOrganizerIds(token));
        }
        verifyNoInteractions(contactRepository);
    }
}

