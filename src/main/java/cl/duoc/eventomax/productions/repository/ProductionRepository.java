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

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from Production p where p.id = :id")
    java.util.Optional<Production> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    default List<Production> findByFilters(ProductionStatus status, LocalDateTime from, LocalDateTime to) {
        return findByFilters(status, from, to, (String) null);
    }

    default List<Production> findByFilters(ProductionStatus status, LocalDateTime from, LocalDateTime to, String organizerId) {
        if (organizerId != null && !organizerId.isBlank()) {
            return findByFilters(status, from, to, java.util.List.of(organizerId.trim()));
        }
        return findByFilters(status, from, to, (java.util.Collection<String>) null);
    }

    default List<Production> findByFilters(ProductionStatus status, LocalDateTime from, LocalDateTime to, java.util.Collection<String> organizerIds) {
        return findAll((root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (organizerIds != null && !organizerIds.isEmpty()) {
                predicates.add(root.get("organizerId").in(organizerIds));
            }
            if (status != null) predicates.add(builder.equal(root.get("status"), status));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.get("scheduledAt"), from));
            if (to != null) predicates.add(builder.lessThanOrEqualTo(root.get("scheduledAt"), to));
            return builder.and(predicates.toArray(Predicate[]::new));
        });
    }

    List<Production> findByOrganizerId(String organizerId);

    List<Production> findByOrganizerIdIn(java.util.Collection<String> organizerIds);
}
