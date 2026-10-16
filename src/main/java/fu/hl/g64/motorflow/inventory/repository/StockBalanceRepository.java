package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockBalanceRepository extends JpaRepository<StockBalance, Long> {
}
