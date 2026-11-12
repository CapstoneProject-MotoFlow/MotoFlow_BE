package fu.hl.g64.motorflow.sales.repository;

import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long>, JpaSpecificationExecutor<SalesOrder> {

    /** Row lock: updating, picking and confirming one order happen one after another (UC-55, UC-57, UC-58). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SalesOrder o where o.id = :id")
    Optional<SalesOrder> findByIdForUpdate(long id);

    /** Orders for the customer, at one store or at every store when {@code storeId} is null. */
    @Query("""
            select o from SalesOrder o
            where o.customerId = :customerId and (:storeId is null or o.storeId = :storeId)""")
    Page<SalesOrder> findByCustomer(Long customerId, Long storeId, Pageable pageable);

    long countByStoreIdAndStatus(Long storeId, SalesOrderStatus status);

    /**
     * Sales summary: [store id, orders completed in [from, to), their revenue (delivered quantity × actual unit price),
     * how many were completed on credit, the original amount of their receivables, what their customers paid (verified
     * payments − verified refunds, with applied corrections)], one row per store with any; every store when
     * {@code storeId} is null.
     */
    @Query(value = """
            SELECT o.store_id,
                   count(*),
                   coalesce(sum(t.total), 0),
                   count(*) FILTER (WHERE o.completion_type = 'CREDIT'),
                   coalesce(sum(r.original_amount), 0),
                   coalesce(sum(m.net), 0)
            FROM sales_order o
            JOIN (SELECT l.sales_order_id, sum(CAST(coalesce(l.delivered_qty, 0) AS BIGINT) * l.actual_unit_price) AS total
                  FROM sales_order_line l GROUP BY l.sales_order_id) t ON t.sales_order_id = o.id
            LEFT JOIN receivable r ON r.sales_order_id = o.id
            LEFT JOIN (SELECT p.sales_order_id,
                              sum(CASE WHEN p.payment_type = 'REFUND' THEN -1 ELSE 1 END
                                  * (p.amount + coalesce(c.amount_change, 0))) AS net
                       FROM payment p
                       LEFT JOIN (SELECT a.payment_id, sum(a.amount_change) AS amount_change
                                  FROM adjustment_amount_line a
                                  JOIN adjustment_request q ON q.id = a.adjustment_request_id
                                  WHERE q.status = 'APPLIED' AND a.payment_id IS NOT NULL
                                  GROUP BY a.payment_id) c ON c.payment_id = p.id
                       WHERE p.status = 'VERIFIED' AND p.payment_type IN ('ORDER_PAYMENT', 'REFUND')
                       GROUP BY p.sales_order_id) m ON m.sales_order_id = o.id
            WHERE o.status = 'COMPLETED' AND o.completed_at >= :from AND o.completed_at < :to
              AND (CAST(:storeId AS BIGINT) IS NULL OR o.store_id = :storeId)
            GROUP BY o.store_id""", nativeQuery = true)
    List<Object[]> sumCompletedByStore(Long storeId, LocalDateTime from, LocalDateTime to);

    /**
     * Sales summary: [store id, debt payments verified in [from, to), the part allocated to the store's receivables
     * (UC-66)], one row per store with any; every store when {@code storeId} is null.
     */
    @Query(value = """
            SELECT r.store_id, coalesce(sum(a.allocated_amount), 0)
            FROM payment p
            JOIN payment_allocation a ON a.payment_id = p.id
            JOIN receivable r ON r.id = a.receivable_id
            WHERE p.payment_type = 'DEBT_PAYMENT' AND p.status = 'VERIFIED'
              AND p.verified_at >= :from AND p.verified_at < :to
              AND (CAST(:storeId AS BIGINT) IS NULL OR r.store_id = :storeId)
            GROUP BY r.store_id""", nativeQuery = true)
    List<Object[]> sumDebtCollectedByStore(Long storeId, LocalDateTime from, LocalDateTime to);

    /** Orders of the store waiting to be picked that no Staff member has been given yet. */
    @Query("""
            select count(o) from SalesOrder o
            where o.storeId = :storeId
              and o.status = fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus.AWAITING_PICKING
              and not exists (select t.id from WarehouseTask t where t.salesOrderId = o.id
                              and t.status <> fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus.CANCELLED)""")
    long countAwaitingPickingWithoutTask(Long storeId);
}
