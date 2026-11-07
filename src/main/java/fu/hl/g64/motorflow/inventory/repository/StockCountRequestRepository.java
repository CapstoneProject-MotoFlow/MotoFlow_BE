package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockCountRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface StockCountRequestRepository extends JpaRepository<StockCountRequest, Long> {

    /** The request, row-locked, so that cancelling it and answering it with a count cannot both happen. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockCountRequest r where r.id = :id")
    Optional<StockCountRequest> findForUpdate(Long id);
}
