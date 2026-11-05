package fu.hl.g64.motorflow.receivable.entity;

import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;
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

/** Money received from or refunded to a customer, for one order or, as a debt payment, for the receivables of its payment_allocation rows (BR-45, BR-52). */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, length = 20)
    private PaymentType paymentType;

    @Column(name = "sales_order_id")
    private Long salesOrderId;

    @Column(nullable = false)
    private long amount;

    @Column(name = "tendered_amount")
    private Long tenderedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "transaction_reference", length = 100)
    private String transactionReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "recorded_by", nullable = false)
    private Long recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "verified_by")
    private Long verifiedBy;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    /**
     * UC-66 steps 5-6: a debt payment the collector has verified on receipt, so it counts at once (BR-46). It belongs to
     * no order; its payment_allocation rows tie it to the receivables it settles.
     */
    public static Payment verifiedDebtPayment(long amount, Long tenderedAmount, PaymentMethod method,
                                              String transactionReference, long collector, LocalDateTime now) {
        Payment payment = new Payment();
        payment.paymentType = PaymentType.DEBT_PAYMENT;
        payment.amount = amount;
        payment.tenderedAmount = tenderedAmount;
        payment.paymentMethod = method;
        payment.transactionReference = transactionReference;
        payment.status = PaymentStatus.VERIFIED;
        payment.recordedBy = collector;
        payment.recordedAt = now;
        payment.verifiedBy = collector;
        payment.verifiedAt = now;
        return payment;
    }
}
