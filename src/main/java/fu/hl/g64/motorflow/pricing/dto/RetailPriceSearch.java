package fu.hl.g64.motorflow.pricing.dto;

import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;

/**
 * UC-34 filters, all optional. {@code q} matches SKU or part name; {@code categoryId} includes its sub-categories;
 * {@code suggestionDiffers} keeps the parts whose suggested price differs from the price in effect, for those who
 * may see suggested prices.
 */
public record RetailPriceSearch(String q, Long categoryId, ProductStatus status, Boolean suggestionDiffers) {
}
