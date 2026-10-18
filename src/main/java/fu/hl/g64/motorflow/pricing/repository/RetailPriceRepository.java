package fu.hl.g64.motorflow.pricing.repository;

import fu.hl.g64.motorflow.pricing.entity.RetailPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface RetailPriceRepository extends JpaRepository<RetailPrice, Long> {

    /** Every version already in effect, oldest first; the last one per product is the current price (BR-09). */
    @Query("select r from RetailPrice r where r.productId in :productIds and r.effectiveFrom <= :now order by r.effectiveFrom")
    List<RetailPrice> findInEffect(Collection<Long> productIds, LocalDateTime now);
}
