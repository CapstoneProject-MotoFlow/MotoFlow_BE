package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.InventoryPeriod;
import fu.hl.g64.motorflow.inventory.entity.enums.InventoryPeriodStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Optional;

public interface InventoryPeriodRepository extends JpaRepository<InventoryPeriod, Long> {

    Optional<InventoryPeriod> findByPeriodMonth(LocalDate periodMonth);

    /** UC-103: the latest closed period that ended before the date. */
    Optional<InventoryPeriod> findFirstByStatusAndToDateBeforeOrderByToDateDesc(InventoryPeriodStatus status,
                                                                               LocalDate date);

    /**
     * Opens the calendar month as a period if it does not exist yet, chained to the latest earlier period.
     * Safe when two transactions do it at once (period_month is unique).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into inventory_period (period_month, from_date, to_date, previous_period_id, status)
            values (:month, :month, :lastDay,
                    (select p.id from inventory_period p where p.period_month < :month
                     order by p.period_month desc limit 1),
                    'OPEN')
            on conflict (period_month) do nothing""", nativeQuery = true)
    void openIfMissing(LocalDate month, LocalDate lastDay);
}
