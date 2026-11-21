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

import java.time.LocalDateTime;

/** PM 21/11: part of a waiting order's deposit taken into the order one of its releases opened. */
@Entity
@Table(name = "sales_deposit_application")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesDepositApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "waiting_order_id", nullable = false)
    private Long waitingOrderId;

    @Column(name = "sales_order_id", nullable = false)
    private Long salesOrderId;

    @Column(nullable = false)
    private long amount;

    @Column(name = "applied_by", nullable = false)
    private Long appliedBy;

    @Column(name = "applied_at", nullable = false)
    private LocalDateTime appliedAt;

    public static SalesDepositApplication of(long waitingOrderId, long salesOrderId, long amount, long appliedBy,
                                             LocalDateTime now) {
        SalesDepositApplication application = new SalesDepositApplication();
        application.waitingOrderId = waitingOrderId;
        application.salesOrderId = salesOrderId;
        application.amount = amount;
        application.appliedBy = appliedBy;
        application.appliedAt = now;
        return application;
    }
}
