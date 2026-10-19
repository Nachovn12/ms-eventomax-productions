package cl.duoc.eventomax.productions.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "production_services",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_production_service", columnNames = {"production_id", "service_id"})
        }
)
public class ProductionContractedService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_id", nullable = false)
    private Production production;

    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    @Column(name = "service_name", nullable = false, length = 150)
    private String serviceName;

    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "contracted_at", nullable = false)
    private LocalDateTime contractedAt;

    @Column(name = "reservation_generation")
    private Long reservationGeneration;

    public ProductionContractedService() {}

    public ProductionContractedService(
            Production production,
            Long serviceId,
            String serviceName,
            BigDecimal unitPrice,
            LocalDateTime contractedAt,
            Long reservationGeneration) {
        this.production = production;
        this.serviceId = serviceId;
        this.serviceName = serviceName;
        this.unitPrice = unitPrice;
        this.contractedAt = contractedAt;
        this.reservationGeneration = reservationGeneration;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Production getProduction() { return production; }
    public void setProduction(Production production) { this.production = production; }
    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long serviceId) { this.serviceId = serviceId; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public LocalDateTime getContractedAt() { return contractedAt; }
    public void setContractedAt(LocalDateTime contractedAt) { this.contractedAt = contractedAt; }
    public Long getReservationGeneration() { return reservationGeneration; }
    public void setReservationGeneration(Long reservationGeneration) { this.reservationGeneration = reservationGeneration; }
}
