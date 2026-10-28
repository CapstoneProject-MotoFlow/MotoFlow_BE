package fu.hl.g64.motorflow.adjustment.repository;

import fu.hl.g64.motorflow.adjustment.entity.AdjustmentRequest;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdjustmentRequestRepository extends JpaRepository<AdjustmentRequest, Long> {

    long countByStatus(AdjustmentRequestStatus status);
}
