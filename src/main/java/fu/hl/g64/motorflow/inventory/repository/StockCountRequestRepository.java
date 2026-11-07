package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockCountRequest;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StockCountRequestRepository extends JpaRepository<StockCountRequest, Long> {

    /** The request, row-locked, so that cancelling it and answering it with a count cannot both happen. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockCountRequest r where r.id = :id")
    Optional<StockCountRequest> findForUpdate(Long id);

    /** UC-105: requests, newest first, optionally of one store and/or in one status. */
    @Query("""
            select r from StockCountRequest r
            where (:storeId is null or r.storeId = :storeId) and (:status is null or r.status = :status)
            order by r.requestedAt desc, r.id desc""")
    List<StockCountRequest> search(Long storeId, StockCountRequestStatus status);
}
