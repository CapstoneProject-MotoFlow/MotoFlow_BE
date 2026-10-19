package fu.hl.g64.motorflow.transfer.entity;

import fu.hl.g64.motorflow.transfer.entity.enums.PartialSupplyDecision;
import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A store's request for more stock, coordinated from other warehouses; any shortage goes to Owner for purchasing (BR-54, BR-55). */
@Entity
@Table(name = "replenishment_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReplenishmentRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_code", nullable = false, unique = true, length = 30)
    private String requestCode;

    @Column(name = "requesting_store_id", nullable = false)
    private Long requestingStoreId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReplenishmentRequestStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "partial_supply_decision", length = 10)
    private PartialSupplyDecision partialSupplyDecision;

    @Column(name = "partial_decided_by")
    private Long partialDecidedBy;

    @Column(name = "partial_decided_at")
    private LocalDateTime partialDecidedAt;

    @Column(length = 500)
    private String note;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}
