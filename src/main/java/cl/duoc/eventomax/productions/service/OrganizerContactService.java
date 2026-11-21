package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.OrganizerContactRequestDTO;
import cl.duoc.eventomax.productions.dto.OrganizerContactResponseDTO;
import cl.duoc.eventomax.productions.model.OrganizerContact;
import cl.duoc.eventomax.productions.repository.OrganizerContactRepository;
import cl.duoc.eventomax.productions.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

@Service
public class OrganizerContactService {

    private static final Logger log = LoggerFactory.getLogger(OrganizerContactService.class);

    private final OrganizerContactRepository contactRepository;

    @org.springframework.beans.factory.annotation.Value("${eventomax.messaging.email.default-recipient:}")
    private String defaultRecipientEmail;

    public OrganizerContactService(OrganizerContactRepository contactRepository) {
        this.contactRepository = contactRepository;
    }

    @Transactional(readOnly = true)
    public OrganizerContactResponseDTO getContact(String organizerId, Authentication authentication, Jwt jwt) {
        validateOrganizerOwnership(organizerId, authentication, jwt);

        OrganizerContact contact = contactRepository.findByOrganizerIdAndActiveTrue(organizerId)
                .orElseThrow(() -> new ResourceNotFoundException("No se encontró contacto registrado para el organizador: " + organizerId));

        return OrganizerContactResponseDTO.fromEntity(contact);
    }

    @Transactional
    public OrganizerContactResponseDTO upsertContact(String organizerId, OrganizerContactRequestDTO request,
                                                     Authentication authentication, Jwt jwt) {
        validateOrganizerOwnership(organizerId, authentication, jwt);

        String actor = (jwt != null) ? SecurityUtils.resolveOwnerId(jwt) : "system";
        boolean isOrganizerSelf = (jwt != null) && SecurityUtils.isOrganizerOnly(authentication, jwt);

        String targetOid = null;
        if (isOrganizerSelf) {
            String callerOid = SecurityUtils.requireOrganizerOid(jwt);
            if (request.userOid() != null && !request.userOid().isBlank()
                    && !callerOid.equalsIgnoreCase(request.userOid().trim())) {
                throw new AccessDeniedException("Organizador no puede reasignar el vinculo de propiedad");
            }
            // Existing links are preserved; new self contacts use the authenticated OID.
            targetOid = callerOid;
        } else if (request.userOid() != null && !request.userOid().isBlank()) {
            if (authentication == null || authentication.getAuthorities().stream()
                    .noneMatch(a -> "ROLE_Admin".equals(a.getAuthority()))) {
                throw new AccessDeniedException("Solo Admin puede asignar el vinculo de propiedad");
            }
            targetOid = request.userOid().trim();
        }

        final String finalOid = targetOid;

        OrganizerContact contact = contactRepository.findByOrganizerId(organizerId)
                .map(existing -> {
                    existing.setEmail(request.email().trim());
                    existing.setContactName(request.contactName() != null ? request.contactName().trim() : null);
                    existing.setPhone(request.phone() != null ? request.phone().trim() : null);
                    if (!isOrganizerSelf && finalOid != null && !finalOid.isBlank()) {
                        existing.setUserOid(finalOid);
                    }
                    existing.setActive(true);
                    existing.setUpdatedBy(actor);
                    return existing;
                })
                .orElseGet(() -> new OrganizerContact(
                        organizerId.trim(),
                        request.email().trim(),
                        request.contactName() != null ? request.contactName().trim() : null,
                        request.phone() != null ? request.phone().trim() : null,
                        finalOid,
                        actor
                ));

        OrganizerContact saved = contactRepository.save(contact);
        log.info("Contacto de organizador actualizado exitosamente");

        return OrganizerContactResponseDTO.fromEntity(saved);
    }

    /**
     * Aprovisiona de forma idempotente el contacto propio del usuario autenticado (tid:oid)
     * utilizando exclusivamente claims validados del JWT.
     * - Si ya existe contacto para organizer_id, NO sobrescribe email ni user_oid (ON CONFLICT DO NOTHING).
     * - Si el JWT no contiene un correo sintácticamente entregable, no inserta fila y deja constancia en log.
     * - Participa en la transacción del llamador para garantizar atomicidad y evitar contactos huérfanos.
     */
    @Transactional
    public boolean ensureSelfContactFromJwt(Jwt jwt) {
        if (jwt == null) {
            return false;
        }
        String ownerId = SecurityUtils.requireOrganizerOwnerId(jwt);
        String oid = SecurityUtils.requireOrganizerOid(jwt);
        String email = SecurityUtils.resolveDeliverableEmail(jwt);
        if (email == null) {
            log.warn("[SELF_CONTACT_SKIPPED] El token para '{}' no entrega un correo entregable apto (ausente, inválido o #EXT#). "
                            + "La producción se creará igualmente, pero las notificaciones no podrán entregarse hasta registrar un contacto autorizado.",
                    ownerId);
            return false;
        }
        String contactName = SecurityUtils.resolveDisplayName(jwt);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int inserted = contactRepository.insertIfAbsent(ownerId, email, contactName, oid, now, ownerId);
        if (inserted > 0) {
            log.info("[SELF_CONTACT_PROVISIONED] Contacto propio aprovisionado automáticamente para '{}'", ownerId);
            return true;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public Optional<String> getVerifiedRecipientEmail(String organizerId) {
        if (organizerId == null || organizerId.isBlank()) {
            return Optional.empty();
        }
        Optional<String> dbEmail = contactRepository.findByOrganizerIdAndActiveTrue(organizerId.trim())
                .map(OrganizerContact::getEmail)
                .filter(email -> email != null && !email.isBlank());
        if (dbEmail.isPresent()) {
            return dbEmail;
        }
        if (defaultRecipientEmail != null && !defaultRecipientEmail.isBlank()) {
            return Optional.of(defaultRecipientEmail.trim());
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public boolean isAuthorizedForOrganizer(String organizerId, Authentication authentication, Jwt jwt) {
        if (!SecurityUtils.isOrganizerOnly(authentication, jwt)) {
            return true;
        }
        if (organizerId == null || organizerId.isBlank() || jwt == null) {
            return false;
        }
        String cleanOrgId = organizerId.trim();
        String callerOwnerId = SecurityUtils.requireOrganizerOwnerId(jwt);
        if (callerOwnerId.equalsIgnoreCase(cleanOrgId)) {
            return true;
        }
        if (SecurityUtils.isCanonicalOwnerId(cleanOrgId)) {
            return false;
        }
        Optional<OrganizerContact> contactOpt = contactRepository.findByOrganizerIdAndActiveTrue(cleanOrgId);
        if (contactOpt.isEmpty()) {
            return false;
        }
        OrganizerContact contact = contactOpt.get();
        String oid = SecurityUtils.requireOrganizerOid(jwt);
        if (oid != null && !oid.isBlank() && oid.equalsIgnoreCase(contact.getUserOid())) {
            return true;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<String> resolveAuthorizedOrganizerIds(Jwt jwt) {
        if (jwt == null) {
            return List.of();
        }
        String ownerId = SecurityUtils.requireOrganizerOwnerId(jwt);
        String oid = SecurityUtils.requireOrganizerOid(jwt);

        LinkedHashSet<String> combined = new LinkedHashSet<>();
        if (oid != null && !oid.isBlank()) {
            contactRepository.findByActiveTrueAndUserOid(oid.trim())
                    .stream().filter(c -> !SecurityUtils.isCanonicalOwnerId(c.getOrganizerId())
                            || ownerId.equalsIgnoreCase(c.getOrganizerId()))
                    .forEach(c -> combined.add(c.getOrganizerId()));
        }
        if (ownerId != null && !ownerId.isBlank()) {
            combined.add(ownerId);
        }
        return new ArrayList<>(combined);
    }

    public void validateOrganizerOwnership(String organizerId, Authentication authentication, Jwt jwt) {
        if (SecurityUtils.isOrganizerOnly(authentication, jwt)) {
            if (!isAuthorizedForOrganizer(organizerId, authentication, jwt)) {
                log.warn("Intento de acceso denegado a organizador ajeno");
                throw new AccessDeniedException("No está autorizado a consultar o administrar información de este organizador");
            }
        }
    }
}
