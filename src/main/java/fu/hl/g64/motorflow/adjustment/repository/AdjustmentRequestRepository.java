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
}
