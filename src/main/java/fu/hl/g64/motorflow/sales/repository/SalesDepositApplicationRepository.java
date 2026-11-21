package fu.hl.g64.motorflow.sales.repository;

import fu.hl.g64.motorflow.sales.entity.SalesDepositApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SalesDepositApplicationRepository extends JpaRepository<SalesDepositApplication, Long> {

    /** What the releases of a waiting order took of its deposit. */
    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM SalesDepositApplication a WHERE a.waitingOrderId = :orderId")
    long sumAppliedFrom(long orderId);

    /** What releases put of deposits into this order (BE-6): it counts as money the order holds. */
    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM SalesDepositApplication a WHERE a.salesOrderId = :orderId")
    long sumAppliedTo(long orderId);
}
