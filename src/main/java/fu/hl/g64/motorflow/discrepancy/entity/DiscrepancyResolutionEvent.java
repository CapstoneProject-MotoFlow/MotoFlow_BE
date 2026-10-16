package fu.hl.g64.motorflow.discrepancy.entity;

import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
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

/** One action taken to resolve a discrepancy case. */
@Entity
@Table(name = "discrepancy_resolution_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiscrepancyResolutionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discrepancy_case_id", nullable = false)
    private Long discrepancyCaseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_type", nullable = false, length = 30)
    private ResolutionType resolutionType;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "goods_receipt_id")
    private Long goodsReceiptId;

    @Column(name = "adjustment_request_id")
    private Long adjustmentRequestId;

    @Column
    private String reason;

    @Column(name = "recorded_by", nullable = false)
    private Long recordedBy;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;
}
