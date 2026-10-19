package fu.hl.g64.motorflow.discrepancy.repository;

import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscrepancyResolutionEventRepository extends JpaRepository<DiscrepancyResolutionEvent, Long> {
}
