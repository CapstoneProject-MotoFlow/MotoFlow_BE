package fu.hl.g64.motorflow.adjustment.helper;

import fu.hl.g64.motorflow.adjustment.dto.AdjustmentAmountView;
import fu.hl.g64.motorflow.adjustment.entity.AdjustmentRequest;
import fu.hl.g64.motorflow.adjustment.entity.AdjustmentStockLine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Corrections of posted documents (UC-81); built in iteration 3 batch 3. */
@Component
@RequiredArgsConstructor
public class DocumentCorrections {

    /** UC-84 step 4 for a correction. */
    public void recheck(AdjustmentRequest request, List<AdjustmentStockLine> lines) {
        throw new IllegalStateException("document corrections are not supported yet: request " + request.getId());
    }

    /** UC-84 step 7 for the amount side of a correction. */
    public void apply(AdjustmentRequest request, long ownerId, LocalDateTime now) {
        throw new IllegalStateException("document corrections are not supported yet: request " + request.getId());
    }

    public List<AdjustmentAmountView> amountViews(AdjustmentRequest request) {
        return List.of();
    }

    public Map<String, Object> correctionView(AdjustmentRequest request) {
        return null;
    }
}
