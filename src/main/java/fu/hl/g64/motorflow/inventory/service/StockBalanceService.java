package fu.hl.g64.motorflow.inventory.service;

import java.time.LocalDateTime;

/** The only way other features write stock_balance (README, section 2). */
public interface StockBalanceService {
    /**
     * UC-05: the new warehouse stores each part still on sale where the template warehouse does, with the same
     * low-stock threshold. Every quantity starts at zero: stock is never copied, so no stock movement is written.
     *
     * @return how many parts got their settings
     */
    public int copySettings(long fromStoreId, long toStoreId, LocalDateTime now);
}
