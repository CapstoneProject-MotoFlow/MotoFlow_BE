package fu.hl.g64.motorflow.pricing.repository;

import fu.hl.g64.motorflow.pricing.entity.PricingPolicy;
import fu.hl.g64.motorflow.pricing.entity.enums.PolicyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface PricingPolicyRepository extends JpaRepository<PricingPolicy, Long> {

    /**
     * Holds the writers of one pricing setting (a part's retail price, the markup rules, a sales limit) one after
     * another until the transaction ends, so two saves cannot both replace the same scheduled version.
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext(:setting))", nativeQuery = true)
    Integer lockSetting(String setting);

    /** Every ACTIVE retail price version of one part, newest first, including one still waiting for its date. */
    @Query("select p from PricingPolicy p where p.policyType = fu.hl.g64.motorflow.pricing.entity.enums.PolicyType.RETAIL_PRICE "
            + "and p.status = fu.hl.g64.motorflow.pricing.entity.enums.PricingPolicyStatus.ACTIVE "
            + "and p.productId = :productId order by p.effectiveFrom desc")
    List<PricingPolicy> findRetailPriceVersions(Long productId);

    /** ACTIVE retail prices that take effect after {@code now}: at most one per part. */
    @Query("select p from PricingPolicy p where p.policyType = fu.hl.g64.motorflow.pricing.entity.enums.PolicyType.RETAIL_PRICE "
            + "and p.status = fu.hl.g64.motorflow.pricing.entity.enums.PricingPolicyStatus.ACTIVE "
            + "and p.productId in :productIds and p.effectiveFrom > :now")
    List<PricingPolicy> findScheduledRetailPrices(Collection<Long> productIds, LocalDateTime now);

    /** Every ACTIVE version of a setting without part or category (discount threshold, minimum margin), newest first. */
    @Query("select p from PricingPolicy p where p.policyType = :type "
            + "and p.status = fu.hl.g64.motorflow.pricing.entity.enums.PricingPolicyStatus.ACTIVE "
            + "and p.productId is null and p.categoryId is null order by p.effectiveFrom desc")
    List<PricingPolicy> findChainVersions(PolicyType type);

    /** The markup configuration in force at {@code now}: the ACTIVE markup rows of the latest effective_from <= now. */
    @Query("select p from PricingPolicy p where p.policyType = fu.hl.g64.motorflow.pricing.entity.enums.PolicyType.MARKUP_RATE "
            + "and p.status = fu.hl.g64.motorflow.pricing.entity.enums.PricingPolicyStatus.ACTIVE "
            + "and p.effectiveFrom = (select max(q.effectiveFrom) from PricingPolicy q "
            + "where q.policyType = fu.hl.g64.motorflow.pricing.entity.enums.PolicyType.MARKUP_RATE "
            + "and q.status = fu.hl.g64.motorflow.pricing.entity.enums.PricingPolicyStatus.ACTIVE and q.effectiveFrom <= :now)")
    List<PricingPolicy> findMarkupRulesInEffect(LocalDateTime now);

    /** The markup configuration saved for a later date, if any. */
    @Query("select p from PricingPolicy p where p.policyType = fu.hl.g64.motorflow.pricing.entity.enums.PolicyType.MARKUP_RATE "
            + "and p.status = fu.hl.g64.motorflow.pricing.entity.enums.PricingPolicyStatus.ACTIVE and p.effectiveFrom > :now")
    List<PricingPolicy> findScheduledMarkupRules(LocalDateTime now);

    /** ACTIVE retail prices already in effect, oldest first; the last one per product is the current price (BR-09). */
    @Query("select p from PricingPolicy p where p.policyType = fu.hl.g64.motorflow.pricing.entity.enums.PolicyType.RETAIL_PRICE "
            + "and p.status = fu.hl.g64.motorflow.pricing.entity.enums.PricingPolicyStatus.ACTIVE "
            + "and p.productId in :productIds and p.effectiveFrom <= :now order by p.effectiveFrom")
    List<PricingPolicy> findRetailPricesInEffect(Collection<Long> productIds, LocalDateTime now);

    /** UC-104: the expiry-warning settings in effect, newest first; the first one applies (BR-76). */
    @Query("select p from PricingPolicy p where p.policyType = fu.hl.g64.motorflow.pricing.entity.enums.PolicyType.EXPIRY_WARNING "
            + "and p.status = fu.hl.g64.motorflow.pricing.entity.enums.PricingPolicyStatus.ACTIVE "
            + "and p.effectiveFrom <= :now order by p.effectiveFrom desc, p.id desc")
    List<PricingPolicy> findExpiryWarningsInEffect(LocalDateTime now);
}
