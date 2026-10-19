package fu.hl.g64.motorflow.transfer.repository;

import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferOrderRepository extends JpaRepository<TransferOrder, Long> {
}
