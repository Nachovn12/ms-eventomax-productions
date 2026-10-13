package cl.duoc.eventomax.productions.repository;

import cl.duoc.eventomax.productions.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = "SELECT * FROM outbox_events o " +
                   "WHERE o.status = 'PENDING' " +
                   "AND NOT EXISTS (" +
                   "    SELECT 1 FROM outbox_events prev " +
                   "    WHERE prev.aggregate_id = o.aggregate_id AND prev.aggregate_type = o.aggregate_type " +
                   "      AND prev.status IN ('PENDING', 'IN_PROGRESS', 'ERROR') " +
                   "      AND (prev.created_at < o.created_at OR (prev.created_at = o.created_at AND prev.sequence_no < o.sequence_no))" +
                   ") " +
                   "ORDER BY o.created_at ASC, o.sequence_no ASC " +
                   "LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> claimNextBatch(@Param("limit") int limit);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OutboxEvent o WHERE o.status = 'IN_PROGRESS' AND o.updatedAt < :cutoff")
    List<OutboxEvent> findStuckEvents(@Param("cutoff") LocalDateTime cutoff);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OutboxEvent o WHERE o.id = :id")
    java.util.Optional<OutboxEvent> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = "SELECT COUNT(*) FROM outbox_events prev " +
                   "WHERE prev.aggregate_type = :aggregateType " +
                   "  AND prev.aggregate_id = :aggregateId " +
                   "  AND prev.status IN ('PENDING', 'IN_PROGRESS', 'ERROR') " +
                   "  AND prev.id != :outboxId " +
                   "  AND (prev.created_at < (SELECT target.created_at FROM outbox_events target WHERE target.id = :outboxId) " +
                   "       OR (prev.created_at = (SELECT target.created_at FROM outbox_events target WHERE target.id = :outboxId) " +
                   "           AND prev.sequence_no < (SELECT target.sequence_no FROM outbox_events target WHERE target.id = :outboxId)))",
           nativeQuery = true)
    long countUnresolvedPredecessors(@Param("outboxId") UUID outboxId,
                                    @Param("aggregateType") String aggregateType,
                                    @Param("aggregateId") String aggregateId);

}
