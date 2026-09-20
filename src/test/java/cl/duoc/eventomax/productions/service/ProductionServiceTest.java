package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionServiceTest {

    @Mock
    private ProductionRepository repository;

    @InjectMocks
    private ProductionService service;

    private Production production;

    @BeforeEach
    void setUp() {
        production = new Production();
        production.setId(1L);
    }

    // K. PUT production inexistente → 404
    @Test
    void updateStatus_missingProduction_throwsResourceNotFoundException() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        
        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO);
        assertThrows(ResourceNotFoundException.class, () -> service.updateStatus(99L, request));
    }

    // L. transición SOLICITADO → CONFIRMADO
    @Test
    void updateStatus_solicitadoToConfirmado_success() {
        production.setStatus(ProductionStatus.SOLICITADO);
        when(repository.findById(1L)).thenReturn(Optional.of(production));
        when(repository.save(any(Production.class))).thenAnswer(i -> i.getArguments()[0]);

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO);
        var result = service.updateStatus(1L, request);
        assertEquals("CONFIRMADO", result.status());
    }

    // M. transición CONFIRMADO → EN_MONTAJE
    @Test
    void updateStatus_confirmadoToEnMontaje_success() {
        production.setStatus(ProductionStatus.CONFIRMADO);
        when(repository.findById(1L)).thenReturn(Optional.of(production));
        when(repository.save(any(Production.class))).thenAnswer(i -> i.getArguments()[0]);

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.EN_MONTAJE);
        var result = service.updateStatus(1L, request);
        assertEquals("EN_MONTAJE", result.status());
    }

    // N. rechazo SOLICITADO → EN_MONTAJE
    @Test
    void updateStatus_solicitadoToEnMontaje_throwsInvalidTransitionException() {
        production.setStatus(ProductionStatus.SOLICITADO);
        when(repository.findById(1L)).thenReturn(Optional.of(production));

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.EN_MONTAJE);
        assertThrows(InvalidTransitionException.class, () -> service.updateStatus(1L, request));
    }

    // O. transición EN_MONTAJE → EN_EJECUCIÓN
    @Test
    void updateStatus_enMontajeToEnEjecucion_success() {
        production.setStatus(ProductionStatus.EN_MONTAJE);
        when(repository.findById(1L)).thenReturn(Optional.of(production));
        when(repository.save(any(Production.class))).thenAnswer(i -> i.getArguments()[0]);

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.EN_EJECUCION);
        var result = service.updateStatus(1L, request);
        assertEquals("EN_EJECUCION", result.status());
    }

    // P. transición EN_EJECUCIÓN → CERRADO
    @Test
    void updateStatus_enEjecucionToCerrado_success() {
        production.setStatus(ProductionStatus.EN_EJECUCION);
        when(repository.findById(1L)).thenReturn(Optional.of(production));
        when(repository.save(any(Production.class))).thenAnswer(i -> i.getArguments()[0]);

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(ProductionStatus.CERRADO);
        var result = service.updateStatus(1L, request);
        assertEquals("CERRADO", result.status());
    }
}

