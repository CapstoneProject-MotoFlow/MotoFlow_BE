package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface StockBalanceRepository extends JpaRepository<StockBalance, Long> {

    /** [warehouseId, number of products with on-hand stock]. Goods in transit belong to no warehouse yet. */
    @Query("""
            select b.warehouseId, count(b) from StockBalance b
            where b.availableQty + b.awaitingInspectionQty + b.wrongItemQty + b.damagedQty
                  + b.awaitingSupplierReturnQty > 0
            group by b.warehouseId""")
    List<Object[]> countProductsInStockByWarehouse();
}
