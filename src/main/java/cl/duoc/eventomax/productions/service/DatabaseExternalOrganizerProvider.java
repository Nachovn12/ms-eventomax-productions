package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.DirectorySyncStatus;
import cl.duoc.eventomax.productions.dto.EligibleOrganizerSource;
import cl.duoc.eventomax.productions.model.OrganizerContact;
import cl.duoc.eventomax.productions.repository.OrganizerContactRepository;
import cl.duoc.eventomax.productions.security.SecurityUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Proveedor local de clientes externos registrados en {@code organizer_contacts}
 * sin identidad Entra ID ({@code user_oid IS NULL}).
 * Nunca convierte un contacto externo en una identidad Entra ID.
 */
@Component
public class DatabaseExternalOrganizerProvider implements EligibleOrganizerProvider {

    private final OrganizerContactRepository contactRepository;

    public DatabaseExternalOrganizerProvider(OrganizerContactRepository contactRepository) {
        this.contactRepository = contactRepository;
    }

    @Override
    public EligibleOrganizerSource source() {
        return EligibleOrganizerSource.EXTERNAL_CONTACT;
    }

    @Override
    @Transactional(readOnly = true)
    public DirectoryFetchResult fetchEligibleOrganizers() {
        List<EligibleOrganizerCandidate> candidates = contactRepository.findByActiveTrueAndUserOidIsNull().stream()
                .filter(c -> !SecurityUtils.isCanonicalOwnerId(c.getOrganizerId()))
                .map(this::toCandidate)
                .toList();
        return new DirectoryFetchResult(candidates, DirectorySyncStatus.OK, true);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EligibleOrganizerCandidate> verifyEligibleOrganizer(String organizerId) {
        if (organizerId == null || organizerId.isBlank()) {
            return Optional.empty();
        }
        String trimmed = organizerId.trim();
        if (SecurityUtils.isCanonicalOwnerId(trimmed)) {
            return Optional.empty();
        }
        return contactRepository.findByOrganizerIdAndActiveTrue(trimmed)
                .filter(c -> c.getUserOid() == null || c.getUserOid().isBlank())
                .map(this::toCandidate);
    }

    private EligibleOrganizerCandidate toCandidate(OrganizerContact contact) {
        String displayName = (contact.getContactName() != null && !contact.getContactName().isBlank())
                ? contact.getContactName().trim()
                : contact.getOrganizerId();
        return new EligibleOrganizerCandidate(
                contact.getOrganizerId(),
                displayName,
                EligibleOrganizerSource.EXTERNAL_CONTACT,
                null,
                contact.getEmail()
        );
    }
}
