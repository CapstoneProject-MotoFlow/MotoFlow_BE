package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.InventoryPeriod;
import fu.hl.g64.motorflow.inventory.entity.enums.InventoryPeriodStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Optional;

public interface InventoryPeriodRepository extends JpaRepository<InventoryPeriod, Long> {

    Optional<InventoryPeriod> findByPeriodMonth(LocalDate periodMonth);

    /**
     * A period about to receive movements, share-locked: postings into one period run side by side, but closing it
     * (which takes {@link #findForClosingById}) waits for them, and they see it CLOSED once it is (MSG85).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from InventoryPeriod p where p.periodMonth = :periodMonth")
    Optional<InventoryPeriod> findForPostingByPeriodMonth(LocalDate periodMonth);

    /** As {@link #findForPostingByPeriodMonth}, for a count adjustment naming its period (BR-83). */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from InventoryPeriod p where p.id = :id")
    Optional<InventoryPeriod> findForPostingById(long id);

    /** UC-100: the period being closed, locked against postings and a second close. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from InventoryPeriod p where p.id = :id")
    Optional<InventoryPeriod> findForClosingById(long id);

    /** UC-100: is there a period after this month? */
    boolean existsByPeriodMonthAfter(LocalDate periodMonth);

    /** UC-103: the latest closed period that ended before the date. */
    Optional<InventoryPeriod> findFirstByStatusAndToDateBeforeOrderByToDateDesc(InventoryPeriodStatus status,
                                                                               LocalDate date);

    /** BR-82: the oldest open period, the only one the Owner may close. */
    Optional<InventoryPeriod> findFirstByStatusOrderByPeriodMonthAsc(InventoryPeriodStatus status);

    /** BR-85: the latest closed period before the month, whose closing balances open the periods after it. */
    Optional<InventoryPeriod> findFirstByStatusAndPeriodMonthBeforeOrderByPeriodMonthDesc(InventoryPeriodStatus status,
                                                                                         LocalDate periodMonth);

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

    /** UC-96: a closed period in or after this month, so a posting into the month would come too late. */
    boolean existsByStatusAndPeriodMonthGreaterThanEqual(InventoryPeriodStatus status, LocalDate periodMonth);
}
