package fu.hl.g64.motorflow.store.repository;

import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    boolean existsByStoreNameIgnoreCase(String storeName);

    boolean existsByStoreNameIgnoreCaseAndIdNot(String storeName, Long id);

    long countByStatus(StoreStatus status);

    Optional<Store> findByIsCentralTrue();

    /** Stores in {@code status}, by name (UC-105: the ACTIVE ones the Owner can ask to count). */
    List<Store> findByStatusOrderByStoreNameAsc(StoreStatus status);

    /**
     * The store row {@code FOR UPDATE}: creating a stock count holds it while it checks the warehouse, so no posting
     * (which reads it {@link #findForShareById FOR SHARE}) slips in between the check and the new count.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Store s where s.id = :id")
    Optional<Store> findForUpdateById(@Param("id") long id);

    /** The store row {@code FOR SHARE}: postings into a warehouse run side by side, not while a count is created. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from Store s where s.id = :id")
    Optional<Store> findForShareById(@Param("id") long id);
}
