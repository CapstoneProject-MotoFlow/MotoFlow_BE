package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockCountLine;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockCountLineRepository extends JpaRepository<StockCountLine, Long> {
}
