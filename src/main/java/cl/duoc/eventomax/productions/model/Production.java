package cl.duoc.eventomax.productions.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "productions")
public class Production {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organizer_id", nullable = false, length = 255)
    private String organizerId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(nullable = false, length = 255)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProductionStatus status = ProductionStatus.SOLICITADO;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Production() {
    }

    public Production(String organizerId, String name, LocalDateTime scheduledAt, String location) {
        this.organizerId = organizerId;
        this.name = name;
        this.scheduledAt = scheduledAt;
        this.location = location;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        if (status == null) {
            status = ProductionStatus.SOLICITADO;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrganizerId() { return organizerId; }
    public void setOrganizerId(String organizerId) { this.organizerId = organizerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public ProductionStatus getStatus() { return status; }
    public void setStatus(ProductionStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
