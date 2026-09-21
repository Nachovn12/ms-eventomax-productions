package cl.duoc.eventomax.productions.repository;

import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductionRepository extends JpaRepository<Production, Long>, JpaSpecificationExecutor<Production> {

    default List<Production> findByFilters(ProductionStatus status, LocalDateTime from, LocalDateTime to) {
        // Bind only supplied filters: PostgreSQL cannot infer timestamp parameters in '? IS NULL'.
        return findAll((root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) predicates.add(builder.equal(root.get("status"), status));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.get("scheduledAt"), from));
            if (to != null) predicates.add(builder.lessThanOrEqualTo(root.get("scheduledAt"), to));
            return builder.and(predicates.toArray(Predicate[]::new));
        });
    }
}
