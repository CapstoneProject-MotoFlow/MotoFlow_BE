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

import java.time.LocalDate;
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

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    /** UC-67: a debt from before MotoFlow, recorded by Owner for one store. Its due date may be in the past. */
    public static Receivable openingBalance(long customerId, long storeId, long amount, LocalDate dueDate, String note,
                                            long recordedBy, LocalDateTime now) {
        Receivable debt = new Receivable();
        debt.originType = OriginType.OPENING_BALANCE;
        debt.customerId = customerId;
        debt.storeId = storeId;
        debt.originalAmount = amount;
        debt.dueDate = dueDate;
        debt.note = note;
        debt.recognizedBy = recordedBy;
        debt.recognizedAt = now;
        return debt;
    }

    /**
     * UC-61 step 8: the unpaid balance of an order completed on credit, with the due date agreed with the customer
     * (BR-50, BR-53). One per order.
     */
    public static Receivable creditSale(long customerId, long storeId, long orderId, long amount, LocalDate dueDate,
                                        long recognizedBy, LocalDateTime now) {
        Receivable debt = new Receivable();
        debt.originType = OriginType.CREDIT_SALE;
        debt.customerId = customerId;
        debt.storeId = storeId;
        debt.salesOrderId = orderId;
        debt.originalAmount = amount;
        debt.dueDate = dueDate;
        debt.recognizedBy = recognizedBy;
        debt.recognizedAt = now;
        return debt;
    }

    /** UC-66 step 7: nothing is owed on it any more. Settled receivables are kept. */
    public void settle(LocalDateTime now) {
        if (settledAt == null) {
            settledAt = now;
        }
    }
}
