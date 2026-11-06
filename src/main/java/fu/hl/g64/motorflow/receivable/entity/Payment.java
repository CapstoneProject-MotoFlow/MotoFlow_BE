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

    /**
     * UC-60 steps 3-6: money the customer paid for an order, verified at once when the seller has seen it arrive
     * (BR-46), or left UNVERIFIED until then. {@code amount} is what goes to the order; the cash handed over is
     * {@code tenderedAmount}, the difference being change returned (BR-47).
     */
    public static Payment orderPayment(long orderId, long amount, Long tenderedAmount, PaymentMethod method,
                                       String transactionReference, boolean verified, long seller,
                                       LocalDateTime now) {
        Payment payment = forOrder(PaymentType.ORDER_PAYMENT, orderId, amount, method, transactionReference,
                verified, seller, now);
        payment.tenderedAmount = tenderedAmount;
        return payment;
    }

    /** UC-56, UC-60 Step 7_Net collection exceeds the total: money given back to the customer before completion. */
    public static Payment refund(long orderId, long amount, PaymentMethod method, String transactionReference,
                                 boolean verified, long seller, LocalDateTime now) {
        return forOrder(PaymentType.REFUND, orderId, amount, method, transactionReference, verified, seller, now);
    }

    private static Payment forOrder(PaymentType type, long orderId, long amount, PaymentMethod method,
                                    String transactionReference, boolean verified, long seller, LocalDateTime now) {
        Payment payment = new Payment();
        payment.paymentType = type;
        payment.salesOrderId = orderId;
        payment.amount = amount;
        payment.paymentMethod = method;
        payment.transactionReference = transactionReference;
        payment.status = PaymentStatus.UNVERIFIED;
        payment.recordedBy = seller;
        payment.recordedAt = now;
        if (verified) {
            payment.verify(seller, now);
        }
        return payment;
    }

    /** BR-46: the seller has checked that the money arrived (or left); from now on it counts. */
    public void verify(long verifier, LocalDateTime now) {
        requireUnverified();
        status = PaymentStatus.VERIFIED;
        verifiedBy = verifier;
        verifiedAt = now;
    }

    /** UC-56 Step 4_Unverified transaction: the money never arrived or was recorded by mistake; it never counts. */
    public void reject(String reason, LocalDateTime now) {
        requireUnverified();
        status = PaymentStatus.REJECTED;
        rejectionReason = reason;
    }

    public boolean isUnverified() {
        return status == PaymentStatus.UNVERIFIED;
    }

    private void requireUnverified() {
        if (status != PaymentStatus.UNVERIFIED) {
            throw new IllegalStateException("payment " + id + " is " + status);
        }
    }
}
