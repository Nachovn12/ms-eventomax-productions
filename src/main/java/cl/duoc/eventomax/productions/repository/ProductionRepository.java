package cl.duoc.eventomax.productions.repository;

import cl.duoc.eventomax.productions.model.Production;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductionRepository extends JpaRepository<Production, Long> {
}