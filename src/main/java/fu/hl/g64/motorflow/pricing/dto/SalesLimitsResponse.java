package fu.hl.g64.motorflow.pricing.dto;

import fu.hl.g64.motorflow.pricing.entity.enums.PricingPolicyStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** UC-38: the discount threshold (BR-11) and the minimum margin (BR-88), each with its versions. */
public record SalesLimitsResponse(SalesLimit discountThreshold, SalesLimit minimumMargin) {

    /**
     * {@code current} is the version in effect and {@code scheduled} the one waiting for its date (each null if none);
     * {@code history} holds every version, newest first.
     */
    public record SalesLimit(RateVersion current, RateVersion scheduled, List<RateVersion> history) {
    }

    /** One version: the rate in percent, the period it applies (effectiveTo null while open) and who set it. */
    public record RateVersion(long id, BigDecimal rate, LocalDateTime effectiveFrom, LocalDateTime effectiveTo,
                              PricingPolicyStatus state, long setById, String setByName, LocalDateTime setAt) {
    }
}
