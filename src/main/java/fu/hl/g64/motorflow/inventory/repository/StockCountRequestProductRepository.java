package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockCountRequestProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface StockCountRequestProductRepository extends JpaRepository<StockCountRequestProduct, Long> {

    List<StockCountRequestProduct> findByStockCountRequestIdOrderByProductId(Long stockCountRequestId);

    List<StockCountRequestProduct> findByStockCountRequestIdIn(Collection<Long> stockCountRequestIds);
}
