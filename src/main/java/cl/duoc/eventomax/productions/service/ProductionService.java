package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.ProductionRequestDTO;
import cl.duoc.eventomax.productions.dto.ProductionResponseDTO;
import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.ProductionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ProductionService {

    private final ProductionRepository repository;

    public ProductionService(ProductionRepository repository) {
        this.repository = repository;
    }


    @Transactional
    public ProductionResponseDTO createProduction(ProductionRequestDTO request) {

        Production production = new Production();

        production.setOrganizerId(request.organizerId());
        production.setName(request.name());
        production.setScheduledAt(request.scheduledAt());
        production.setLocation(request.location());
        production.setStatus(ProductionStatus.SOLICITADO);

        Production saved = repository.save(production);

        return toResponseDTO(saved);
    }


    @Transactional(readOnly = true)
    public Optional<ProductionResponseDTO> getProductionById(Long id) {

        return repository.findById(id)
                .map(this::toResponseDTO);

    }


    @Transactional(readOnly = true)
    public List<ProductionResponseDTO> getAllProductions() {

        return repository.findAll()
                .stream()
                .map(this::toResponseDTO)
                .toList();

    }


    @Transactional
    public ProductionResponseDTO updateStatus(Long id, ProductionStatusUpdateDTO request) {

        Production production = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Produccion no encontrada con id: " + id));

        validateTransition(production.getStatus(), request.status());

        production.setStatus(request.status());
        Production updated = repository.save(production);

        return toResponseDTO(updated);
    }


    // -------------------------------------------------------------------------
    // Transitions
    // -------------------------------------------------------------------------

    /**
     * Allowed transitions map.
     * Key   = current status
     * Value = set of statuses the current one can transition TO
     *
     * Main rule (README): EN_MONTAJE requires CONFIRMADO as previous state.
     */
    private static final Map<ProductionStatus, Set<ProductionStatus>> ALLOWED_TRANSITIONS =
            Map.of(
                    ProductionStatus.SOLICITADO,  EnumSet.of(ProductionStatus.CONFIRMADO,
                                                             ProductionStatus.CANCELADO),
                    ProductionStatus.CONFIRMADO,  EnumSet.of(ProductionStatus.EN_MONTAJE,
                                                             ProductionStatus.CANCELADO),
                    ProductionStatus.EN_MONTAJE,  EnumSet.of(ProductionStatus.EN_EJECUCION,
                                                             ProductionStatus.CANCELADO),
                    ProductionStatus.EN_EJECUCION, EnumSet.of(ProductionStatus.CERRADO,
                                                              ProductionStatus.CANCELADO),
                    ProductionStatus.CERRADO,     EnumSet.noneOf(ProductionStatus.class),
                    ProductionStatus.CANCELADO,   EnumSet.noneOf(ProductionStatus.class)
            );

    private void validateTransition(ProductionStatus current, ProductionStatus next) {

        Set<ProductionStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(
                current, EnumSet.noneOf(ProductionStatus.class));

        if (!allowed.contains(next)) {
            throw new InvalidTransitionException(
                    "Transicion invalida: " + current + " -> " + next
                    + ". Transiciones permitidas desde " + current + ": " + allowed);
        }
    }


    // -------------------------------------------------------------------------
    // Mapper
    // -------------------------------------------------------------------------

    private ProductionResponseDTO toResponseDTO(Production production) {

        return new ProductionResponseDTO(
                production.getId(),
                production.getOrganizerId(),
                production.getName(),
                production.getScheduledAt(),
                production.getLocation(),
                production.getStatus().name(),
                production.getCreatedAt(),
                production.getUpdatedAt()
        );
    }
}

