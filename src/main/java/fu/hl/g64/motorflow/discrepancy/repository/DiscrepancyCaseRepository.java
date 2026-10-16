package fu.hl.g64.motorflow.discrepancy.repository;

import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscrepancyCaseRepository extends JpaRepository<DiscrepancyCase, Long> {
}
