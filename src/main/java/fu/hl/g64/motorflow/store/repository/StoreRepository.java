package fu.hl.g64.motorflow.store.repository;

import fu.hl.g64.motorflow.store.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, Long> {

    boolean existsByStoreNameIgnoreCase(String storeName);

    boolean existsByStoreNameIgnoreCaseAndIdNot(String storeName, Long id);
}
