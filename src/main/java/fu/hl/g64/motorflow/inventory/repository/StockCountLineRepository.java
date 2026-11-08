package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.entity.StockCountLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockCountLineRepository extends JpaRepository<StockCountLine, Long> {

    /** The count's lines by part, then expiry (lines without a lot last), then id. */
    List<StockCountLine> findByStockCountIdOrderByProductIdAscExpiryDateAscIdAsc(Long stockCountId);
}
