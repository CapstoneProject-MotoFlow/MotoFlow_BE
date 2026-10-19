package fu.hl.g64.motorflow.pricing.repository;

import fu.hl.g64.motorflow.pricing.entity.WholesalePrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface WholesalePriceRepository extends JpaRepository<WholesalePrice, Long> {

    /** The price in effect at one store (BR-10). */
    Optional<WholesalePrice> findFirstByProductIdAndStoreIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            Long productId, Long storeId, LocalDateTime now);
}
