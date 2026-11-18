package fu.hl.g64.motorflow.adjustment.repository;

import fu.hl.g64.motorflow.adjustment.entity.AdjustmentRequest;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AdjustmentRequestRepository extends JpaRepository<AdjustmentRequest, Long>,
        JpaSpecificationExecutor<AdjustmentRequest> {

    long countByStatus(AdjustmentRequestStatus status);

    /** Row lock: deciding, applying and withdrawing one request happen one after another (UC-84). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AdjustmentRequest a where a.id = :id")
    Optional<AdjustmentRequest> findByIdForUpdate(long id);

    /** UC-81 step 4: a correction of the same document that is still waiting or approved and not applied. */
    @Query("""
            select a.id from AdjustmentRequest a
            where a.adjustmentType = fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentType.DOCUMENT_CORRECTION
              and a.status in (fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.PENDING,
                               fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus.APPROVED)
              and (a.salesOrderId = :salesOrderId or a.goodsReceiptNoteId = :receiptId or a.paymentId = :paymentId
                   or a.receivableId = :receivableId)""")
    java.util.List<Long> findOpenCorrections(Long salesOrderId, Long receiptId, Long paymentId, Long receivableId);
}
