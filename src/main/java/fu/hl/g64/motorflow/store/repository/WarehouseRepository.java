package fu.hl.g64.motorflow.store.repository;

import fu.hl.g64.motorflow.store.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    Optional<Warehouse> findByStoreId(Long storeId);

    Optional<Warehouse> findByIsCentralTrue();
}
