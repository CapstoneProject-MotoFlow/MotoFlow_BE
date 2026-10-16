package fu.hl.g64.motorflow.transfer.repository;

import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferOrderLineRepository extends JpaRepository<TransferOrderLine, Long> {
}
