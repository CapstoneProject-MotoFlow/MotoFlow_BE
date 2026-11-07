package fu.hl.g64.motorflow.transfer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A part moved on a transfer order. */
@Entity
@Table(name = "transfer_order_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransferOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_order_id", nullable = false)
    private Long transferOrderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "planned_qty", nullable = false)
    private int plannedQty;

    @Column(name = "prepared_qty")
    private Integer preparedQty;

    @Column(name = "dispatched_qty")
    private Integer dispatchedQty;

    @Column(name = "extra_dispatched_qty", nullable = false)
    private int extraDispatchedQty;

    @Column(name = "makeup_for_case_id")
    private Long makeupForCaseId;

    /** UC-57: what the person preparing the dispatch actually took out; never more than planned. */
    public void recordPrepared(int preparedQty) {
        if (preparedQty < 0 || preparedQty > plannedQty) {
            throw new IllegalArgumentException("prepared quantity must be 0.." + plannedQty);
        }
        this.preparedQty = preparedQty;
    }
}
