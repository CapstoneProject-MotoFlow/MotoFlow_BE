package fu.hl.g64.motorflow.pricing.service;

import fu.hl.g64.motorflow.pricing.dto.MarkupPreviewRequest;
import fu.hl.g64.motorflow.pricing.dto.MarkupRulesRequest;
import fu.hl.g64.motorflow.pricing.dto.MarkupRulesResponse;
import fu.hl.g64.motorflow.pricing.dto.SalesLimitRequest;
import fu.hl.g64.motorflow.pricing.dto.SalesLimitsResponse;
import fu.hl.g64.motorflow.pricing.dto.SuggestionPreview;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.util.List;

/**
 * UC-38 Configure Discount Threshold and Minimum Margin, UC-101 Configure Price Suggestion Rules (BR-11, BR-69,
 * BR-88). Owner only. Every setting is versioned by effective date: today applies right away, a later day at its
 * start, and a new version for a later day replaces the one waiting.
 */
public interface PricingRuleService {

    /** UC-38: both limits with the version in effect, the one waiting and their history. */
    SalesLimitsResponse salesLimits();

    /** UC-38: saves a new discount threshold or minimum margin from the chosen day. MSG125 a day before today. */
    SalesLimitsResponse setSalesLimit(AuthenticatedUser owner, SalesLimitRequest request);

    /** UC-101: the markup configuration in force and the one waiting for its date. */
    MarkupRulesResponse markupRules();

    /**
     * UC-101: saves the whole markup configuration from the chosen day. MSG128 no rate at all, MSG127 a category or
     * part given twice, MSG61 or MSG57 an unknown category or part, MSG125 a day before today.
     */
    MarkupRulesResponse setMarkupRules(AuthenticatedUser owner, MarkupRulesRequest request);

    /**
     * UC-101: suggested prices of sample parts under a configuration that is not saved; nothing is written (BR-69).
     * MSG127 a category or part given twice, MSG57 an unknown part.
     */
    List<SuggestionPreview> preview(MarkupPreviewRequest request);
}
