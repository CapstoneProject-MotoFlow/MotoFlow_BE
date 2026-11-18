package fu.hl.g64.motorflow.adjustment.repository;

import fu.hl.g64.motorflow.adjustment.entity.AdjustmentAmountLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface AdjustmentAmountLineRepository extends JpaRepository<AdjustmentAmountLine, Long> {

    List<AdjustmentAmountLine> findByAdjustmentRequestIdOrderByIdAsc(Long adjustmentRequestId);

    /** Request id and the sum of its money changes, for the requests that have any. */
    @Query("""
            select l.adjustmentRequestId, sum(l.amountChange) from AdjustmentAmountLine l
            where l.adjustmentRequestId in :requestIds
            group by l.adjustmentRequestId""")
    List<Object[]> sumAmountByAdjustmentRequest(Collection<Long> requestIds);
}
