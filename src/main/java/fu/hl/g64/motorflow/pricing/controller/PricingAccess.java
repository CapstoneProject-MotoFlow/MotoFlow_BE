package fu.hl.g64.motorflow.pricing.controller;

/** Who may use pricing (BR-08, Permission Matrix). Staff and Admin have no access to it. */
final class PricingAccess {

    /** View the price list and price history. */
    static final String VIEW = "hasAnyRole('OWNER', 'BRANCH_MANAGER')";
    /** Update a retail price, also by applying the suggested price (BR-08). */
    static final String EDIT_PRICE = "hasRole('OWNER') or (hasRole('BRANCH_MANAGER') and hasAuthority('PERM_RETAIL_PRICE_EDIT'))";
    /** Discount threshold, minimum margin, markup rates and rounding. */
    static final String CONFIGURE = "hasRole('OWNER')";

    private PricingAccess() {
    }
}
