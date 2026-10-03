package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.ProductionReservationItemDTO;
import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.integration.catalog.exception.CatalogUnavailableException;
import cl.duoc.eventomax.productions.integration.catalog.exception.InventoryReservationException;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductionServiceTest {

    @Mock
    private ProductionRepository repository;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Mock
    private CatalogClient catalogClient;

    @InjectMocks
    private ProductionService service;

    private Production production;

    @BeforeEach
    void setUp() {
        production = new Production();
        production.setId(1L);
    }

    @Test
    void updateStatus_missingProduction_throwsResourceNotFoundException() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO, List.of(new ProductionReservationItemDTO(1L, 2)));
        assertThrows(ResourceNotFoundException.class, () -> service.updateStatus(99L, request));
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(catalogClient);
    }

    // A. SOLICITADO -> CONFIRMADO con items válidos
    @Test
    void updateStatus_solicitadoToConfirmado_withItems_success() {
        production.setStatus(ProductionStatus.SOLICITADO);
        when(repository.findById(1L)).thenReturn(Optional.of(production));
        when(repository.saveAndFlush(any(Production.class))).thenAnswer(i -> i.getArguments()[0]);

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(
                ProductionStatus.CONFIRMADO,
                List.of(new ProductionReservationItemDTO(1L, 2))
        );
        var result = service.updateStatus(1L, request);

        assertEquals("CONFIRMADO", result.status());
        verify(catalogClient, times(1)).reserveInventory(eq(1L), anyList());
        verify(repository, times(1)).saveAndFlush(production);
        verify(eventPublisher, times(1)).publishEvent(any(cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent.class));
    }

    // B. CONFIRMADO sin items
    @Test
    void updateStatus_solicitadoToConfirmado_withoutItems_throwsIllegalArgumentException() {
        production.setStatus(ProductionStatus.SOLICITADO);
        when(repository.findById(1L)).thenReturn(Optional.of(production));

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO, null);
        assertThrows(IllegalArgumentException.class, () -> service.updateStatus(1L, request));

        assertEquals(ProductionStatus.SOLICITADO, production.getStatus());
        verifyNoInteractions(catalogClient);
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    // C. Catalog rechaza reserva
    @Test
    void updateStatus_solicitadoToConfirmado_catalogRejects_throwsInventoryReservationException() {
        production.setStatus(ProductionStatus.SOLICITADO);
        when(repository.findById(1L)).thenReturn(Optional.of(production));

        List<ProductionReservationItemDTO> items = List.of(new ProductionReservationItemDTO(1L, 2));
        doThrow(new InventoryReservationException("Rejected")).when(catalogClient).reserveInventory(1L, items);

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO, items);

        assertThrows(InventoryReservationException.class, () -> service.updateStatus(1L, request));

        assertEquals(ProductionStatus.SOLICITADO, production.getStatus());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    // D. Catalog no disponible
    @Test
    void updateStatus_solicitadoToConfirmado_catalogUnavailable_throwsCatalogUnavailableException() {
        production.setStatus(ProductionStatus.SOLICITADO);
        when(repository.findById(1L)).thenReturn(Optional.of(production));

        List<ProductionReservationItemDTO> items = List.of(new ProductionReservationItemDTO(1L, 2));
        doThrow(new CatalogUnavailableException("Unavailable")).when(catalogClient).reserveInventory(1L, items);

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO, items);

        assertThrows(CatalogUnavailableException.class, () -> service.updateStatus(1L, request));

        assertEquals(ProductionStatus.SOLICITADO, production.getStatus());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    // E. CONFIRMADO -> EN_MONTAJE
    @Test
    void updateStatus_confirmadoToEnMontaje_success() {
        production.setStatus(ProductionStatus.CONFIRMADO);
        when(repository.findById(1L)).thenReturn(Optional.of(production));
        when(repository.saveAndFlush(any(Production.class))).thenAnswer(i -> i.getArguments()[0]);

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.EN_MONTAJE);
        var result = service.updateStatus(1L, request);

        assertEquals("EN_MONTAJE", result.status());
        verifyNoInteractions(catalogClient);
        verify(eventPublisher, times(1)).publishEvent(any(cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent.class));
    }

    // F. transición inválida SOLICITADO -> EN_MONTAJE
    @Test
    void updateStatus_solicitadoToEnMontaje_throwsInvalidTransitionException() {
        production.setStatus(ProductionStatus.SOLICITADO);
        when(repository.findById(1L)).thenReturn(Optional.of(production));

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.EN_MONTAJE);
        assertThrows(InvalidTransitionException.class, () -> service.updateStatus(1L, request));

        verifyNoInteractions(catalogClient);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void updateStatus_enMontajeToEnEjecucion_success() {
        production.setStatus(ProductionStatus.EN_MONTAJE);
        when(repository.findById(1L)).thenReturn(Optional.of(production));
        when(repository.saveAndFlush(any(Production.class))).thenAnswer(i -> i.getArguments()[0]);

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.EN_EJECUCION);
        var result = service.updateStatus(1L, request);
        assertEquals("EN_EJECUCION", result.status());
        verify(eventPublisher).publishEvent(any(cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent.class));
    }

    @Test
    void updateStatus_enEjecucionToCerrado_success() {
        production.setStatus(ProductionStatus.EN_EJECUCION);
        when(repository.findById(1L)).thenReturn(Optional.of(production));
        when(repository.saveAndFlush(any(Production.class))).thenAnswer(i -> i.getArguments()[0]);

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.CERRADO);
        var result = service.updateStatus(1L, request);
        assertEquals("CERRADO", result.status());
        verify(eventPublisher).publishEvent(any(cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent.class));
    }

    @Test
    void updateStatus_cerradoIsTerminal_throwsInvalidTransitionException() {
        production.setStatus(ProductionStatus.CERRADO);
        when(repository.findById(1L)).thenReturn(Optional.of(production));

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.CANCELADO);
        assertThrows(InvalidTransitionException.class, () -> service.updateStatus(1L, request));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void updateStatus_canceladoIsTerminal_throwsInvalidTransitionException() {
        production.setStatus(ProductionStatus.CANCELADO);
        when(repository.findById(1L)).thenReturn(Optional.of(production));

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO);
        assertThrows(InvalidTransitionException.class, () -> service.updateStatus(1L, request));
        verifyNoInteractions(eventPublisher);
    }
}
