package fu.hl.g64.motorflow.transfer.repository;

import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequest;
import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplenishmentRequestRepository extends JpaRepository<ReplenishmentRequest, Long> {

    long countByStatus(ReplenishmentRequestStatus status);
}
