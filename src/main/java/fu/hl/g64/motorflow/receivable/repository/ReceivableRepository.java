package fu.hl.g64.motorflow.receivable.repository;

import fu.hl.g64.motorflow.receivable.entity.Receivable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceivableRepository extends JpaRepository<Receivable, Long> {
}
