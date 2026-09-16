package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.ProductionRequestDTO;
import cl.duoc.eventomax.productions.dto.ProductionResponseDTO;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.ProductionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

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

        return toResponse(saved);
    }


    @Transactional(readOnly = true)
    public Optional<ProductionResponseDTO> getProductionById(Long id) {

        return repository.findById(id)
                .map(this::toResponse);
    }


    private ProductionResponseDTO toResponse(Production production) {

        return new ProductionResponseDTO(
                production.getId(),
                production.getName(),
                production.getScheduledAt(),
                production.getLocation(),
                production.getStatus().name()
        );
    }
}
