package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.LotBalance;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LotBalanceRepository extends JpaRepository<LotBalance, Long> {

    /** Creates the empty row if it is missing; safe when two transactions do it at once. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into lot_balance (lot_id, store_id, stock_status, quantity, updated_at)
            values (:lotId, :storeId, :status, 0, :now)
            on conflict (lot_id, store_id, stock_status) do nothing""", nativeQuery = true)
    void insertIfMissing(Long lotId, Long storeId, String status, LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from LotBalance b where b.lotId = :lotId and b.storeId = :storeId and b.stockStatus = :status")
    Optional<LotBalance> findForUpdate(Long lotId, Long storeId, StockStatus status);

    /**
     * Lots of a part in one warehouse and status that still hold stock, in picking order: earliest expiry first
     * (lots without expiry last), then oldest received (FEFO, then FIFO). Row-locked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from LotBalance b, Lot l
            where l.id = b.lotId and l.productId = :productId
              and b.storeId = :storeId and b.stockStatus = :status and b.quantity > 0
            order by l.expiryDate asc nulls last, l.receivedDate asc, l.id asc""")
    List<LotBalance> findPickableForUpdate(Long storeId, Long productId, StockStatus status);

    @Query("select coalesce(sum(b.quantity), 0) from LotBalance b where b.lotId = :lotId")
    long sumQuantityOfLot(Long lotId);
}
