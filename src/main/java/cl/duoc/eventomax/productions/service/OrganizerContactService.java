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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

@Service
public class OrganizerContactService {

    private static final Logger log = LoggerFactory.getLogger(OrganizerContactService.class);

    private final OrganizerContactRepository contactRepository;

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

        // Si se provee userOid explícito (ej. asignado administrativamente), se utiliza ese valor.
        // Si quien llama es el propio organizador, se toma su propio OID del token.
        // Si quien llama es Admin o Productor y NO se especifica userOid, NO se asigna el OID del administrador.
        String targetOid = null;
        if (request.userOid() != null && !request.userOid().isBlank()) {
            targetOid = request.userOid().trim();
        } else if (isOrganizerSelf) {
            targetOid = SecurityUtils.resolveOid(jwt);
        }

        final String finalOid = targetOid;

        OrganizerContact contact = contactRepository.findByOrganizerId(organizerId)
                .map(existing -> {
                    existing.setEmail(request.email().trim());
                    existing.setContactName(request.contactName() != null ? request.contactName().trim() : null);
                    existing.setPhone(request.phone() != null ? request.phone().trim() : null);
                    if (finalOid != null && !finalOid.isBlank()) {
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
        log.info("Contacto de organizador actualizado exitosamente: organizerId={}, email={}, userOid={}, actor={}",
                organizerId, saved.getEmail(), saved.getUserOid(), actor);

        return OrganizerContactResponseDTO.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public Optional<String> getVerifiedRecipientEmail(String organizerId) {
        if (organizerId == null || organizerId.isBlank()) {
            return Optional.empty();
        }
        return contactRepository.findByOrganizerIdAndActiveTrue(organizerId.trim())
                .map(OrganizerContact::getEmail)
                .filter(email -> email != null && !email.isBlank());
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
        String callerOwnerId = SecurityUtils.resolveOwnerId(jwt);
        if (callerOwnerId.equalsIgnoreCase(cleanOrgId)) {
            return true;
        }
        Optional<OrganizerContact> contactOpt = contactRepository.findByOrganizerIdAndActiveTrue(cleanOrgId);
        if (contactOpt.isEmpty()) {
            return false;
        }
        OrganizerContact contact = contactOpt.get();
        String oid = SecurityUtils.resolveOid(jwt);
        if (oid != null && !oid.isBlank() && oid.equalsIgnoreCase(contact.getUserOid())) {
            return true;
        }
        String email = SecurityUtils.resolveEmail(jwt);
        if (email != null && !email.isBlank() && email.equalsIgnoreCase(contact.getEmail())) {
            return true;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<String> resolveAuthorizedOrganizerIds(Jwt jwt) {
        if (jwt == null) {
            return List.of();
        }
        String ownerId = SecurityUtils.resolveOwnerId(jwt);
        String oid = SecurityUtils.resolveOid(jwt);
        String email = SecurityUtils.resolveEmail(jwt);

        LinkedHashSet<String> combined = new LinkedHashSet<>();
        if (oid != null && !oid.isBlank()) {
            contactRepository.findByActiveTrueAndUserOid(oid.trim())
                    .forEach(c -> combined.add(c.getOrganizerId()));
        }
        if (email != null && !email.isBlank()) {
            contactRepository.findByActiveTrueAndEmailIgnoreCase(email.trim())
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
                String callerOwnerId = (jwt != null) ? SecurityUtils.resolveOwnerId(jwt) : "unknown";
                log.warn("Intento de acceso denegado a organizador ajeno: caller={}, targetOrganizer={}",
                        callerOwnerId, organizerId);
                throw new AccessDeniedException("No está autorizado a consultar o administrar información de este organizador");
            }
        }
    }
}
