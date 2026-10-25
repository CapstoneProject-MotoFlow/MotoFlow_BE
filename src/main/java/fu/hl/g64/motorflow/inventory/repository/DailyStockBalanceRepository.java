package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.DailyStockBalance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyStockBalanceRepository extends JpaRepository<DailyStockBalance, Long> {
}
