package fu.hl.g64.motorflow.adjustment.repository;

import fu.hl.g64.motorflow.adjustment.entity.AdjustmentAmountLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdjustmentAmountLineRepository extends JpaRepository<AdjustmentAmountLine, Long> {

    List<AdjustmentAmountLine> findByAdjustmentRequestIdOrderByIdAsc(Long adjustmentRequestId);
}
