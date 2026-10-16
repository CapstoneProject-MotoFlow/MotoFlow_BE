package fu.hl.g64.motorflow.sales.entity;

import fu.hl.g64.motorflow.sales.entity.enums.UnmetDemandStatus;
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

/** Parts a customer still needs from an existing order that the store could not supply (UC-60). */
@Entity
@Table(name = "unmet_demand")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UnmetDemand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sales_order_id", nullable = false)
    private Long salesOrderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "unmet_qty", nullable = false)
    private int unmetQty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private UnmetDemandStatus status;

    @Column(name = "recorded_by", nullable = false)
    private Long recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "fulfilled_by_order_id")
    private Long fulfilledByOrderId;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "close_reason")
    private String closeReason;
}
