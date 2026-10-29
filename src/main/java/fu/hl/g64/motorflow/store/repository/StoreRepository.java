package fu.hl.g64.motorflow.store.repository;

import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    boolean existsByStoreNameIgnoreCase(String storeName);

    boolean existsByStoreNameIgnoreCaseAndIdNot(String storeName, Long id);

    long countByStatus(StoreStatus status);

    Optional<Store> findByIsCentralTrue();
}
