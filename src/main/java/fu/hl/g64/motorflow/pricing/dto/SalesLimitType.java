package fu.hl.g64.motorflow.pricing.dto;

import fu.hl.g64.motorflow.pricing.entity.enums.PolicyType;

/** UC-38: the two limits Owner sets for selling, each saved on its own. */
public enum SalesLimitType {
    /** Maximum invoice-wide discount rate (BR-11). */
    DISCOUNT_THRESHOLD(PolicyType.DISCOUNT_THRESHOLD),
    /** Minimum margin over the moving average cost (BR-88). */
    MINIMUM_MARGIN(PolicyType.MINIMUM_MARGIN);

    private final PolicyType policyType;

    SalesLimitType(PolicyType policyType) {
        this.policyType = policyType;
    }

    public PolicyType policyType() {
        return policyType;
    }
}
