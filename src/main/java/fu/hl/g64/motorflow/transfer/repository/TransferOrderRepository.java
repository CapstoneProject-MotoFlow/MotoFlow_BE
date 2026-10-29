package fu.hl.g64.motorflow.transfer.repository;

import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferOrderRepository extends JpaRepository<TransferOrder, Long> {

    long countBySourceStoreIdAndStatus(Long sourceStoreId, TransferOrderStatus status);
}
