package fu.hl.g64.motorflow.sales.repository;

import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {
}
