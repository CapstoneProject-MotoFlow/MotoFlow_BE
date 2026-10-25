package fu.hl.g64.motorflow.pricing.repository;

import fu.hl.g64.motorflow.pricing.entity.PricingPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface PricingPolicyRepository extends JpaRepository<PricingPolicy, Long> {

    /** ACTIVE retail prices already in effect, oldest first; the last one per product is the current price (BR-09). */
    @Query("select p from PricingPolicy p where p.policyType = fu.hl.g64.motorflow.pricing.entity.enums.PolicyType.RETAIL_PRICE "
            + "and p.status = fu.hl.g64.motorflow.pricing.entity.enums.PricingPolicyStatus.ACTIVE "
            + "and p.productId in :productIds and p.effectiveFrom <= :now order by p.effectiveFrom")
    List<PricingPolicy> findRetailPricesInEffect(Collection<Long> productIds, LocalDateTime now);
}
