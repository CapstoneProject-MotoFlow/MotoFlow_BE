package fu.hl.g64.motorflow.transfer.repository;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequestLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReplenishmentRequestLineRepository extends JpaRepository<ReplenishmentRequestLine, Long> {

    List<ReplenishmentRequestLine> findByReplenishmentRequestIdOrderById(Long replenishmentRequestId);

    interface LowStockPart {
        StockBalance getBalance();

        Product getProduct();
    }

    /** Screen 7.1.2: active parts below threshold at the store that no unclosed request of the store already asks for. */
    @Query("""
            select b as balance, p as product
            from StockBalance b
            join Product p on p.id = b.productId
            where b.storeId = :storeId
              and b.lowStockThreshold is not null
              and b.availableQty < b.lowStockThreshold
              and p.status = fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus.ACTIVE
              and not exists (
                  select 1
                  from ReplenishmentRequestLine l
                  join ReplenishmentRequest r on r.id = l.replenishmentRequestId
                  where r.requestingStoreId = b.storeId
                    and l.productId = b.productId
                    and r.status <> fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus.CLOSED)
            order by p.sku
            """)
    List<LowStockPart> findLowStockParts(@Param("storeId") long storeId);
}
