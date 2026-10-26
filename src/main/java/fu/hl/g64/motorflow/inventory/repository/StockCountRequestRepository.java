package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockCountRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockCountRequestRepository extends JpaRepository<StockCountRequest, Long> {
}
