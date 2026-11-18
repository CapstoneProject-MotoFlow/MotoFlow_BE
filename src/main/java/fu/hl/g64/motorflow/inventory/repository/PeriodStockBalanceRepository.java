package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.PeriodStockBalance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PeriodStockBalanceRepository extends JpaRepository<PeriodStockBalance, Long> {

    /** UC-99: the balances stored when the period was closed. */
    List<PeriodStockBalance> findByInventoryPeriodId(Long inventoryPeriodId);
}
