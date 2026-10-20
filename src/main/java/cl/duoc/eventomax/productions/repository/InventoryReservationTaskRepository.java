package cl.duoc.eventomax.productions.repository;

import cl.duoc.eventomax.productions.model.InventoryReservationTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;

public interface InventoryReservationTaskRepository extends JpaRepository<InventoryReservationTask, Long> {
    Optional<InventoryReservationTask> findByProductionId(Long productionId);

    @Query("SELECT t FROM InventoryReservationTask t WHERE t.status IN ('PENDING', 'PROCESSING', 'COMPENSATING') AND t.updatedAt < :cutoff ORDER BY t.updatedAt ASC")
    List<InventoryReservationTask> findClaimableTasks(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT t FROM InventoryReservationTask t WHERE t.status IN ('PENDING', 'PROCESSING', 'RESERVED', 'COMPENSATING') AND t.updatedAt < :cutoff ORDER BY t.updatedAt ASC")
    List<InventoryReservationTask> findStuckTasks(@Param("cutoff") LocalDateTime cutoff);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE InventoryReservationTask t SET t.status = 'PROCESSING', t.attemptCount = t.attemptCount + 1, t.updatedAt = :now WHERE t.id = :id AND t.status = :expectedStatus AND t.attemptCount = :expectedAttempt")
    int claimTask(
            @Param("id") Long id,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedAttempt") Integer expectedAttempt,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true)
    @Query("UPDATE InventoryReservationTask t SET t.status = 'CONFIRMED', t.lastError = null, t.updatedAt = :now " +
           "WHERE t.productionId = :productionId AND t.reservationGeneration = :generation " +
           "AND t.status IN ('PROCESSING', 'CONFIRMED')")
    int markConfirmedForGeneration(
            @Param("productionId") Long productionId,
            @Param("generation") Long generation,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true)
    @Query("UPDATE InventoryReservationTask t SET t.status = 'CONFIRMED', t.lastError = null, t.updatedAt = :now " +
           "WHERE t.productionId = :productionId AND t.reservationGeneration IS NULL " +
           "AND t.status IN ('PROCESSING', 'CONFIRMED')")
    int markConfirmedLegacy(
            @Param("productionId") Long productionId,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true)
    @Query("UPDATE InventoryReservationTask t SET t.status = 'COMPENSATING', t.lastError = :error, t.updatedAt = :now " +
           "WHERE t.productionId = :productionId AND t.reservationGeneration = :generation " +
           "AND t.status != 'COMPENSATED'")
    int markCompensatingForGeneration(
            @Param("productionId") Long productionId,
            @Param("generation") Long generation,
            @Param("error") String error,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true)
    @Query("UPDATE InventoryReservationTask t SET t.status = 'COMPENSATING', t.lastError = :error, t.updatedAt = :now " +
           "WHERE t.productionId = :productionId AND t.reservationGeneration IS NULL " +
           "AND t.status != 'COMPENSATED'")
    int markCompensatingLegacy(
            @Param("productionId") Long productionId,
            @Param("error") String error,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true)
    @Query("UPDATE InventoryReservationTask t SET t.status = 'COMPENSATED', t.lastError = null, t.updatedAt = :now " +
           "WHERE t.productionId = :productionId AND t.reservationGeneration = :generation " +
           "AND t.status IN ('COMPENSATING', 'PROCESSING', 'CONFIRMED', 'COMPENSATED')")
    int markCompensatedForGeneration(
            @Param("productionId") Long productionId,
            @Param("generation") Long generation,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true)
    @Query("UPDATE InventoryReservationTask t SET t.status = 'COMPENSATED', t.lastError = null, t.updatedAt = :now " +
           "WHERE t.productionId = :productionId AND t.reservationGeneration IS NULL " +
           "AND t.status IN ('COMPENSATING', 'PROCESSING', 'CONFIRMED', 'COMPENSATED')")
    int markCompensatedLegacy(
            @Param("productionId") Long productionId,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true)
    @Query("UPDATE InventoryReservationTask t SET t.status = 'FAILED', t.lastError = :error, t.updatedAt = :now " +
           "WHERE t.productionId = :productionId AND t.reservationGeneration = :generation " +
           "AND t.status NOT IN ('CONFIRMED', 'COMPENSATED')")
    int markFailedForGeneration(
            @Param("productionId") Long productionId,
            @Param("generation") Long generation,
            @Param("error") String error,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true)
    @Query("UPDATE InventoryReservationTask t SET t.status = 'FAILED', t.lastError = :error, t.updatedAt = :now " +
           "WHERE t.productionId = :productionId AND t.reservationGeneration IS NULL " +
           "AND t.status NOT IN ('CONFIRMED', 'COMPENSATED')")
    int markFailedLegacy(
            @Param("productionId") Long productionId,
            @Param("error") String error,
            @Param("now") LocalDateTime now
    );
}
