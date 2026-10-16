package fu.hl.g64.motorflow.receivable.entity;

import fu.hl.g64.motorflow.receivable.entity.enums.OriginType;
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

/** An amount a customer owes a store: from an order completed on credit or an opening balance. */
@Entity
@Table(name = "receivable")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Receivable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_type", nullable = false, length = 20)
    private OriginType originType;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "sales_order_id", unique = true)
    private Long salesOrderId;

    @Column(name = "original_amount", nullable = false)
    private long originalAmount;

    @Column(length = 500)
    private String note;

    @Column(name = "recognized_by", nullable = false)
    private Long recognizedBy;

    @Column(name = "recognized_at", nullable = false)
    private LocalDateTime recognizedAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;
}
