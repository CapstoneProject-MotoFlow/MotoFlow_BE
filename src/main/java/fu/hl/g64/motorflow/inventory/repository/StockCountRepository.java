package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockCount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockCountRepository extends JpaRepository<StockCount, Long> {
}
