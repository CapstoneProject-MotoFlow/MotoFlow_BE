package fu.hl.g64.motorflow.sales.repository;

import fu.hl.g64.motorflow.sales.entity.UnmetDemand;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnmetDemandRepository extends JpaRepository<UnmetDemand, Long> {
}
