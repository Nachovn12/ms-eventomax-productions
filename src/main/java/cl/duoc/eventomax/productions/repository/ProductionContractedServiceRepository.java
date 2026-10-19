package cl.duoc.eventomax.productions.repository;

import cl.duoc.eventomax.productions.model.ProductionContractedService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductionContractedServiceRepository extends JpaRepository<ProductionContractedService, Long> {

    @Query("SELECT s FROM ProductionContractedService s WHERE s.production.id = :productionId ORDER BY s.serviceId ASC")
    List<ProductionContractedService> findByProductionId(@Param("productionId") Long productionId);

    @Modifying
    @Query("DELETE FROM ProductionContractedService s WHERE s.production.id = :productionId")
    void deleteByProductionId(@Param("productionId") Long productionId);
}
