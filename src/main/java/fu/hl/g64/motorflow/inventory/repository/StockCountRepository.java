package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.Optional;

public interface StockCountRepository extends JpaRepository<StockCount, Long> {

    long countByStatus(StockCountStatus status);

    /** The count, row-locked, so that two people acting on it at once cannot both change it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StockCount c where c.id = :id")
    Optional<StockCount> findForUpdate(Long id);

    /** SC-1: the warehouse already has a count in one of these statuses. */
    boolean existsByStoreIdAndStatusIn(Long storeId, Collection<StockCountStatus> statuses);
}
