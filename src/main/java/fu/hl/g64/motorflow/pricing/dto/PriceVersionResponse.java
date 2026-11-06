package fu.hl.g64.motorflow.pricing.dto;

import fu.hl.g64.motorflow.pricing.entity.enums.PricingPolicyStatus;

import java.time.LocalDateTime;

/**
 * UC-36: one retail price version of a part. {@code change} is the difference from the version before it (null for
 * the first); {@code effectiveTo} is when the next version starts (null while none follows). {@code state} follows
 * from the dates: SCHEDULED waits for its date, ACTIVE is in effect, SUPERSEDED was replaced. {@code fromSuggestion}
 * and {@code reason} come from the change record and are null for versions saved outside the application.
 */
public record PriceVersionResponse(long id, long unitPrice, Long change, LocalDateTime effectiveFrom,
                                   LocalDateTime effectiveTo, PricingPolicyStatus state, Boolean fromSuggestion,
                                   String reason, long setById, String setByName, LocalDateTime setAt) {
}
