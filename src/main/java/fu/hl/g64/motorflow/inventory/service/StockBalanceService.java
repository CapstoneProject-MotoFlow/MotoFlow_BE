package fu.hl.g64.motorflow.inventory.service;

import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** The only way other features write stock_balance (README, section 2). */
@Service
@RequiredArgsConstructor
public class StockBalanceService {

    private final StockBalanceRepository balances;

    /**
     * UC-05: the new warehouse stores each part still on sale where the template warehouse does, with the same
     * low-stock threshold. Every quantity starts at zero: stock is never copied, so no stock movement is written.
     *
     * @return how many parts got their settings
     */
    @Transactional
    public int copySettings(long fromStoreId, long toStoreId, LocalDateTime now) {
        List<StockBalance> copies = balances.findSettingsOfActiveProducts(fromStoreId).stream()
                .map(template -> StockBalance.withSettingsOf(template, toStoreId, now))
                .toList();
        balances.saveAll(copies);
        return copies.size();
    }
}
