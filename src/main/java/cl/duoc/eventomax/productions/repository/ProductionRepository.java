package cl.duoc.eventomax.productions.repository;

import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ProductionRepository extends JpaRepository<Production, Long> {

    @Query("SELECT p FROM Production p WHERE "
            + "(:status IS NULL OR p.status = :status) AND "
            + "(:from IS NULL OR p.scheduledAt >= :from) AND "
            + "(:to IS NULL OR p.scheduledAt <= :to)")
    List<Production> findByFilters(
            @Param("status") ProductionStatus status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}