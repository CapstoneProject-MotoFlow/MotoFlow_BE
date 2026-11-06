package fu.hl.g64.motorflow.pricing.controller;

import fu.hl.g64.motorflow.pricing.dto.MarkupPreviewRequest;
import fu.hl.g64.motorflow.pricing.dto.MarkupRulesRequest;
import fu.hl.g64.motorflow.pricing.dto.MarkupRulesResponse;
import fu.hl.g64.motorflow.pricing.dto.SalesLimitRequest;
import fu.hl.g64.motorflow.pricing.dto.SalesLimitsResponse;
import fu.hl.g64.motorflow.pricing.dto.SuggestionPreview;
import fu.hl.g64.motorflow.pricing.service.PricingRuleService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Pricing rules", description = "UC-38 discount threshold and minimum margin, UC-101 price suggestion "
        + "rules: Owner only")
@RestController
@RequestMapping("/api/v1/pricing")
@PreAuthorize(PricingAccess.CONFIGURE)
@RequiredArgsConstructor
public class PricingRuleController {

    private final PricingRuleService pricingRules;

    @Operation(summary = "Discount threshold and minimum margin: in effect, waiting and history (UC-38)")
    @GetMapping("/sales-limits")
    public SalesLimitsResponse salesLimits() {
        return pricingRules.salesLimits();
    }

    @Operation(summary = "Save a new discount threshold or minimum margin from a day (UC-38)")
    @PutMapping("/sales-limits")
    public SalesLimitsResponse setSalesLimit(@AuthenticationPrincipal AuthenticatedUser owner,
                                             @Valid @RequestBody SalesLimitRequest request) {
        return pricingRules.setSalesLimit(owner, request);
    }

    @Operation(summary = "Markup and rounding rules in force and waiting for their date (UC-101)")
    @GetMapping("/markup-rules")
    public MarkupRulesResponse markupRules() {
        return pricingRules.markupRules();
    }

    @Operation(summary = "Save the whole markup configuration from a day (UC-101)")
    @PostMapping("/markup-rules")
    public MarkupRulesResponse setMarkupRules(@AuthenticationPrincipal AuthenticatedUser owner,
                                              @Valid @RequestBody MarkupRulesRequest request) {
        return pricingRules.setMarkupRules(owner, request);
    }

    @Operation(summary = "Suggested prices of sample parts under rules that are not saved (UC-101)")
    @PostMapping("/markup-rules/preview")
    public List<SuggestionPreview> preview(@Valid @RequestBody MarkupPreviewRequest request) {
        return pricingRules.preview(request);
    }
}
