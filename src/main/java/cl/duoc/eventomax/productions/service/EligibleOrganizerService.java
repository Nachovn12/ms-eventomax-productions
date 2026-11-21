package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.DirectorySyncStatus;
import cl.duoc.eventomax.productions.dto.EligibleOrganizerDTO;
import cl.duoc.eventomax.productions.dto.EligibleOrganizerSource;
import cl.duoc.eventomax.productions.dto.EligibleOrganizersPageDTO;
import cl.duoc.eventomax.productions.model.OrganizerContact;
import cl.duoc.eventomax.productions.repository.OrganizerContactRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Orquesta los {@link EligibleOrganizerProvider} registrados para construir
 * el selector paginado de organizadores elegibles y revalidar elegibilidad en servidor.
 */
@Service
public class EligibleOrganizerService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    private final List<EligibleOrganizerProvider> providers;
    private final OrganizerContactRepository contactRepository;

    public EligibleOrganizerService(
            List<EligibleOrganizerProvider> providers,
            OrganizerContactRepository contactRepository) {
        this.providers = providers;
        this.contactRepository = contactRepository;
    }

    @Transactional(readOnly = true)
    public EligibleOrganizersPageDTO listEligibleOrganizers(String query, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = (size <= 0) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String cleanQuery = (query == null) ? "" : query.trim().toLowerCase(Locale.ROOT);

        DirectorySyncStatus directoryStatus = DirectorySyncStatus.DISABLED;
        Map<String, EligibleOrganizerCandidate> byId = new LinkedHashMap<>();

        for (EligibleOrganizerProvider provider : providers) {
            DirectoryFetchResult result = provider.fetchEligibleOrganizers();
            if (provider.source() == EligibleOrganizerSource.ENTRA_ORGANIZER) {
                directoryStatus = result.status();
            }
            for (EligibleOrganizerCandidate candidate : result.candidates()) {
                byId.putIfAbsent(candidate.organizerId(), candidate);
            }
        }

        List<EligibleOrganizerDTO> all = new ArrayList<>();
        for (EligibleOrganizerCandidate candidate : byId.values()) {
            Optional<OrganizerContact> registered = contactRepository.findByOrganizerIdAndActiveTrue(candidate.organizerId());
            boolean hasContact = registered.isPresent()
                    && registered.get().getEmail() != null
                    && !registered.get().getEmail().isBlank();
            String maskedEmail = hasContact ? maskEmail(registered.get().getEmail()) : null;

            EligibleOrganizerDTO dto = new EligibleOrganizerDTO(
                    candidate.organizerId(),
                    candidate.displayName(),
                    candidate.source(),
                    hasContact,
                    maskedEmail
            );

            if (matchesQuery(dto, cleanQuery)) {
                all.add(dto);
            }
        }

        all.sort(Comparator.comparing(
                dto -> dto.displayName() != null ? dto.displayName().toLowerCase(Locale.ROOT) : ""));

        int total = all.size();
        int fromIndex = Math.min(safePage * safeSize, total);
        int toIndex = Math.min(fromIndex + safeSize, total);
        List<EligibleOrganizerDTO> slice = all.subList(fromIndex, toIndex);

        return new EligibleOrganizersPageDTO(List.copyOf(slice), safePage, safeSize, total, directoryStatus);
    }

    /**
     * Revalida en servidor (fail-closed) que un {@code organizerId} canónico corresponde
     * a un usuario Entra ID habilitado con el App Role {@code Organizador}.
     */
    public EligibleOrganizerCandidate requireVerifiedEntraOrganizer(String organizerId) {
        EligibleOrganizerProvider entraProvider = providers.stream()
                .filter(p -> p.source() == EligibleOrganizerSource.ENTRA_ORGANIZER)
                .findFirst()
                .orElseThrow(() -> new ExternalDirectoryUnavailableException(
                        "Proveedor de directorio Entra ID no disponible."));

        return entraProvider.verifyEligibleOrganizer(organizerId)
                .orElseThrow(() -> new AccessDeniedException(
                        "El usuario seleccionado no es un Organizador elegible y habilitado en EventoMax."));
    }

    /**
     * Revalida en servidor que un cliente externo existe, está activo y no pertenece a una identidad Entra ID.
     */
    @Transactional(readOnly = true)
    public EligibleOrganizerCandidate requireExistingExternalOrganizer(String organizerId) {
        EligibleOrganizerProvider dbProvider = providers.stream()
                .filter(p -> p.source() == EligibleOrganizerSource.EXTERNAL_CONTACT)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Cliente externo no encontrado: " + organizerId));

        return dbProvider.verifyEligibleOrganizer(organizerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No existe un cliente externo activo registrado para el identificador: " + organizerId));
    }

    private static boolean matchesQuery(EligibleOrganizerDTO dto, String cleanQuery) {
        if (cleanQuery.isEmpty()) {
            return true;
        }
        boolean matchesName = dto.displayName() != null
                && dto.displayName().toLowerCase(Locale.ROOT).contains(cleanQuery);
        if (matchesName) {
            return true;
        }
        return dto.source() == EligibleOrganizerSource.EXTERNAL_CONTACT
                && dto.organizerId() != null
                && dto.organizerId().toLowerCase(Locale.ROOT).contains(cleanQuery);
    }

    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        if (at <= 0 || at >= trimmed.length() - 1) {
            return "***";
        }
        String local = trimmed.substring(0, at);
        String domain = trimmed.substring(at);
        String prefix = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return prefix + "***" + domain;
    }
}
