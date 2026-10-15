package cl.duoc.eventomax.productions.repository;

import cl.duoc.eventomax.productions.model.InventoryReservationTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InventoryReservationTaskRepository extends JpaRepository<InventoryReservationTask, Long> {
    Optional<InventoryReservationTask> findByProductionId(Long productionId);

    @Query("SELECT t FROM InventoryReservationTask t WHERE t.status IN ('PENDING', 'RESERVED', 'COMPENSATING') AND t.updatedAt < :cutoff")
    List<InventoryReservationTask> findStuckTasks(@Param("cutoff") LocalDateTime cutoff);
}
