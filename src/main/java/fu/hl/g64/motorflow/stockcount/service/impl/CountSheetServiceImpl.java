package fu.hl.g64.motorflow.stockcount.service.impl;

import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.StockCountLine;
import fu.hl.g64.motorflow.inventory.repository.LotBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountLineRepository;
import fu.hl.g64.motorflow.stockcount.service.CountSheetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CountSheetServiceImpl implements CountSheetService {

    private final StockCountLineRepository lines;
    private final LotBalanceRepository lotBalances;
    private final StockBalanceRepository stockBalances;

    // region buildLines (owner: Y)
    @Override
    public List<StockCountLine> buildLines(StockCount count, List<Long> productIds) {
        throw new UnsupportedOperationException("UC-91");
    }
    // endregion

    // region captureBookQuantities (owner: Y)
    @Override
    public void captureBookQuantities(StockCount count, List<StockCountLine> countLines) {
        throw new UnsupportedOperationException("UC-93");
    }
    // endregion

    // region hasDifference (owner: Z)
    @Override
    public boolean hasDifference(List<StockCountLine> countLines) {
        throw new UnsupportedOperationException("UC-94");
    }
    // endregion
}
