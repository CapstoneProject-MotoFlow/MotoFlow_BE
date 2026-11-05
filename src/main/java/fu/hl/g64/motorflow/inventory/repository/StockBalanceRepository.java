package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockBalanceRepository extends JpaRepository<StockBalance, Long> {

    /** [storeId, number of products with on-hand stock]. Goods in transit belong to no warehouse yet. */
    @Query("""
            select b.storeId, count(b) from StockBalance b
            where b.availableQty + b.awaitingInspectionQty + b.wrongItemQty + b.damagedQty
                  + b.awaitingSupplierReturnQty > 0
            group by b.storeId""")
    List<Object[]> countProductsInStockByStore();

    /** Products a warehouse still holds or is about to receive; a store cannot close while this is above zero. */
    @Query("""
            select count(b) from StockBalance b
            where b.storeId = :storeId
              and b.availableQty + b.awaitingInspectionQty + b.wrongItemQty + b.damagedQty
                  + b.awaitingSupplierReturnQty + b.inTransitQty > 0""")
    long countProductsHeldBy(Long storeId);

    /** Parts still on sale that the store is set up to sell (a row in its warehouse, e.g. copied in UC-05). */
    @Query("""
            select b.productId from StockBalance b join Product p on p.id = b.productId
            where b.storeId = :storeId
              and p.status = fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus.ACTIVE""")
    List<Long> findActiveProductIdsOf(Long storeId);

    List<StockBalance> findByStoreIdAndProductIdIn(Long storeId, Collection<Long> productIds);

    /** Rows of a warehouse that place a part still on sale or set its low-stock threshold (UC-05). */
    @Query("""
            select b from StockBalance b join Product p on p.id = b.productId
            where b.storeId = :storeId
              and p.status = fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus.ACTIVE
              and (b.storageArea is not null or b.binLocation is not null or b.lowStockThreshold is not null)
            order by b.productId""")
    List<StockBalance> findSettingsOfActiveProducts(Long storeId);

    /** Creates the empty row if it is missing; safe when two transactions do it at once. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into stock_balance (store_id, product_id, updated_at)
            values (:storeId, :productId, :now)
            on conflict (store_id, product_id) do nothing""", nativeQuery = true)
    void insertIfMissing(Long storeId, Long productId, LocalDateTime now);

    /** Row lock that serialises every posting for one part in one warehouse (BR-29). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from StockBalance b where b.storeId = :storeId and b.productId = :productId")
    Optional<StockBalance> findForUpdate(Long storeId, Long productId);

    /**
     * Chain-wide quantity of a part that carries its moving average: everything held except goods that are not
     * accepted from the supplier (wrong item, damaged, awaiting return to supplier).
     */
    @Query("""
            select coalesce(sum(b.availableQty + b.awaitingInspectionQty + b.inTransitQty), 0)
            from StockBalance b where b.productId = :productId""")
    long sumValuedQuantity(Long productId);
}
