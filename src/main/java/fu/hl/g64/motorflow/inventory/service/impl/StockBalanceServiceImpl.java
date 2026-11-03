package fu.hl.g64.motorflow.inventory.service.impl;

import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.service.StockBalanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StockBalanceServiceImpl implements StockBalanceService {

    private final StockBalanceRepository balances;

    @Transactional
    @Override
    public int copySettings(long fromStoreId, long toStoreId, LocalDateTime now) {
        List<StockBalance> copies = balances.findSettingsOfActiveProducts(fromStoreId).stream()
                .map(template -> StockBalance.withSettingsOf(template, toStoreId, now))
                .toList();
        balances.saveAll(copies);
        return copies.size();
    }
}
