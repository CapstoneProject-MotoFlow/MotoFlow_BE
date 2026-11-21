package fu.hl.g64.motorflow.sales.repository;

import fu.hl.g64.motorflow.sales.entity.SalesDepositExpiryChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SalesDepositExpiryChangeRepository extends JpaRepository<SalesDepositExpiryChange, Long> {

    List<SalesDepositExpiryChange> findBySalesOrderIdOrderByChangedAtAscIdAsc(long salesOrderId);
}
