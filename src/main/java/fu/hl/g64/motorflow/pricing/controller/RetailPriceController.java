package fu.hl.g64.motorflow.pricing.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.pricing.dto.PriceVersionResponse;
import fu.hl.g64.motorflow.pricing.dto.RetailPriceRequest;
import fu.hl.g64.motorflow.pricing.dto.RetailPriceResponse;
import fu.hl.g64.motorflow.pricing.dto.RetailPriceSearch;
import fu.hl.g64.motorflow.pricing.service.RetailPriceService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Retail prices", description = "UC-34 to UC-36: the chain-wide price list for Owner and Branch Manager; "
        + "updates need Owner or the retail price permission")
@RestController
@RequestMapping("/api/v1/pricing/retail-prices")
@PreAuthorize(PricingAccess.VIEW)
@RequiredArgsConstructor
public class RetailPriceController {

    private final RetailPriceService retailPrices;

    @Operation(summary = "Price list: price in effect, next price and, for those allowed, the suggested price (UC-34)")
    @GetMapping
    public PageResponse<RetailPriceResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                  RetailPriceSearch filter,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return retailPrices.list(user, filter, page, size);
    }

    @Operation(summary = "One part of the price list (UC-34)")
    @GetMapping("/{productId}")
    public RetailPriceResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long productId) {
        return retailPrices.get(user, productId);
    }

    @Operation(summary = "Set the retail price of a part from a day, typed or the suggested price (UC-35)")
    @PostMapping
    @PreAuthorize(PricingAccess.EDIT_PRICE)
    public RetailPriceResponse update(@AuthenticationPrincipal AuthenticatedUser actor,
                                      @Valid @RequestBody RetailPriceRequest request) {
        return retailPrices.update(actor, request);
    }

    @Operation(summary = "Retail price history of a part, newest first (UC-36)")
    @GetMapping("/{productId}/history")
    public List<PriceVersionResponse> history(@PathVariable long productId) {
        return retailPrices.history(productId);
    }
}
