package fu.hl.g64.motorflow.sales.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** PM 21/11: one change of a deposit's expiry date by the store's Branch Manager. */
@Entity
@Table(name = "sales_deposit_expiry_change")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesDepositExpiryChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sales_order_id", nullable = false)
    private Long salesOrderId;

    @Column(name = "old_date", nullable = false)
    private LocalDate oldDate;

    @Column(name = "new_date", nullable = false)
    private LocalDate newDate;

    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    public static SalesDepositExpiryChange of(long salesOrderId, LocalDate oldDate, LocalDate newDate, long changedBy,
                                              LocalDateTime now) {
        SalesDepositExpiryChange change = new SalesDepositExpiryChange();
        change.salesOrderId = salesOrderId;
        change.oldDate = oldDate;
        change.newDate = newDate;
        change.changedBy = changedBy;
        change.changedAt = now;
        return change;
    }
}
