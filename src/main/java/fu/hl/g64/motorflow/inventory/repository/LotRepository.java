package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.Lot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LotRepository extends JpaRepository<Lot, Long> {
}
