package fu.hl.g64.motorflow.pricing.dto;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.pricing.entity.PricingPolicy;
import fu.hl.g64.motorflow.pricing.entity.enums.RoundingRule;
import fu.hl.g64.motorflow.pricing.helper.PriceSuggestions.Suggestion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * UC-34: one part of the chain-wide price list. {@code currentPrice} is the price in effect (null if the part has
 * none yet) and {@code scheduledPrice} the one waiting for its date. {@code suggestedPrice} (BR-69) is filled only
 * for Owner and a Branch Manager holding retail price permission, and is null when the part has no supplier receipt
 * or no markup rate; {@code purchaseBasis} is cost data for Owner only (BR-72).
 */
public record RetailPriceResponse(long productId, String sku, String partName, Long categoryId, String categoryName,
                                  String baseUnit, ProductStatus status, Long currentPrice, LocalDateTime currentFrom,
                                  Long scheduledPrice, LocalDateTime scheduledFrom, Long suggestedPrice,
                                  PurchaseBasis purchaseBasis) {

    public static RetailPriceResponse of(Product part, String categoryName, PricingPolicy current,
                                         PricingPolicy scheduled, Suggestion suggestion, boolean costShown) {
        PurchaseBasis basis = costShown && suggestion != null
                ? new PurchaseBasis(suggestion.latestPurchasePrice(), suggestion.receivedDate(), suggestion.markupRate(),
                suggestion.roundingRule())
                : null;
        return new RetailPriceResponse(part.getId(), part.getSku(), part.getPartName(), part.getCategoryId(),
                categoryName, part.getBaseUnit(), part.getStatus(),
                current == null ? null : current.getUnitPrice(), current == null ? null : current.getEffectiveFrom(),
                scheduled == null ? null : scheduled.getUnitPrice(),
                scheduled == null ? null : scheduled.getEffectiveFrom(),
                suggestion == null ? null : suggestion.suggestedPrice(), basis);
    }

    /** The suggested price exists and is not the price in effect: the part may need a price update. */
    public boolean suggestionDiffers() {
        return suggestedPrice != null && !suggestedPrice.equals(currentPrice);
    }

    /** What the suggestion is built from (BR-69): the lot price of the latest supplier receipt and the markup rate. */
    public record PurchaseBasis(Long latestPurchasePrice, LocalDate receivedDate, BigDecimal markupRate,
                                RoundingRule roundingRule) {
    }
}
