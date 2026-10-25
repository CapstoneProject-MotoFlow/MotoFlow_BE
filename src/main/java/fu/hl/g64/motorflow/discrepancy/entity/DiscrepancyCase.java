package fu.hl.g64.motorflow.discrepancy.entity;

import fu.hl.g64.motorflow.discrepancy.entity.enums.ClosureType;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;
import fu.hl.g64.motorflow.discrepancy.entity.enums.SourceType;
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

/** Short, wrong, damaged or rejected-excess goods of one part on one supplier receipt, or a transfer shortage, tracked until resolved. */
@Entity
@Table(name = "discrepancy_case")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiscrepancyCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 10)
    private SourceType sourceType;

    @Column(name = "goods_receipt_id")
    private Long goodsReceiptId;

    @Column(name = "transfer_order_id")
    private Long transferOrderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "short_qty", nullable = false)
    private int shortQty;

    @Column(name = "wrong_item_qty", nullable = false)
    private int wrongItemQty;

    @Column(name = "damaged_qty", nullable = false)
    private int damagedQty;

    @Column(name = "rejected_excess_qty", nullable = false)
    private int rejectedExcessQty;

    @Column(name = "return_required_qty", nullable = false)
    private int returnRequiredQty;

    @Column(name = "replacement_required_qty", nullable = false)
    private int replacementRequiredQty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private DiscrepancyCaseStatus status;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "closure_type", length = 25)
    private ClosureType closureType;

    @Column(name = "closure_reason")
    private String closureReason;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}
