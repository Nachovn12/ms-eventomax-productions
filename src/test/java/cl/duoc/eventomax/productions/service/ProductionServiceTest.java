package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.ProductionResponseDTO;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionServiceTest {

    @Mock
    private ProductionRepository repository;

    @Test
    void getFilteredProductions_rejectsInvalidStatus() {
        ProductionService service = new ProductionService(repository);

        InvalidProductionStatusException exception = assertThrows(
                InvalidProductionStatusException.class,
                () -> service.getFilteredProductions("NO_EXISTE", null, null));

        assertThat(exception).hasMessageContaining("NO_EXISTE");
    }

    @Test
    void getFilteredProductions_acceptsValidStatus() {
        ProductionService service = new ProductionService(repository);
        when(repository.findByFilters(ProductionStatus.CONFIRMADO, null, null))
                .thenReturn(List.of());

        List<ProductionResponseDTO> result =
                service.getFilteredProductions("CONFIRMADO", null, null);

        assertThat(result).isEmpty();
        verify(repository).findByFilters(ProductionStatus.CONFIRMADO, null, null);
    }
}
