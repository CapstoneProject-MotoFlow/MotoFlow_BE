package fu.hl.g64.motorflow.adjustment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Amount correction requested by an adjustment (BR-49, BR-53). */
@Entity
@Table(name = "adjustment_amount_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdjustmentAmountLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "adjustment_request_id", nullable = false)
    private Long adjustmentRequestId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "receivable_id")
    private Long receivableId;

    @Column(name = "amount_change", nullable = false)
    private long amountChange;

    /** A change of a payment's amount (BR-49). */
    public static AdjustmentAmountLine ofPayment(long requestId, long paymentId, long amountChange) {
        AdjustmentAmountLine line = new AdjustmentAmountLine();
        line.adjustmentRequestId = requestId;
        line.paymentId = paymentId;
        line.amountChange = amountChange;
        return line;
    }

    /** A change of what a receivable still asks for (BR-53, BR-71). */
    public static AdjustmentAmountLine ofReceivable(long requestId, long receivableId, long amountChange) {
        AdjustmentAmountLine line = new AdjustmentAmountLine();
        line.adjustmentRequestId = requestId;
        line.receivableId = receivableId;
        line.amountChange = amountChange;
        return line;
    }
}
