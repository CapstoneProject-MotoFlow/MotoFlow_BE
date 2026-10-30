package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

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

    List<StockBalance> findByStoreIdAndProductIdIn(Long storeId, Collection<Long> productIds);

    /** Rows of a warehouse that place a part still on sale or set its low-stock threshold (UC-05). */
    @Query("""
            select b from StockBalance b join Product p on p.id = b.productId
            where b.storeId = :storeId
              and p.status = fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus.ACTIVE
              and (b.storageArea is not null or b.binLocation is not null or b.lowStockThreshold is not null)
            order by b.productId""")
    List<StockBalance> findSettingsOfActiveProducts(Long storeId);
}
