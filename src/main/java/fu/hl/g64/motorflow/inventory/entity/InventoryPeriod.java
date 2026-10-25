package fu.hl.g64.motorflow.inventory.entity;

import fu.hl.g64.motorflow.inventory.entity.enums.InventoryPeriodStatus;
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

/** One calendar month for the whole chain; closed in month order by Owner and never reopened. */
@Entity
@Table(name = "inventory_period")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_month", nullable = false, unique = true)
    private LocalDate periodMonth;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "previous_period_id", unique = true)
    private Long previousPeriodId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InventoryPeriodStatus status;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}
