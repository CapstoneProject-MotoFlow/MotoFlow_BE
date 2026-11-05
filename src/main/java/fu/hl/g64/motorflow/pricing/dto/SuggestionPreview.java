package fu.hl.g64.motorflow.pricing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * UC-101 preview row (Owner only, BR-72): the price in effect, the latest purchase price with its receipt date, the
 * markup rate that applies and the suggested price it gives (null without purchase or rate).
 */
public record SuggestionPreview(long productId, String sku, String partName, Long currentPrice,
                                Long latestPurchasePrice, LocalDate receivedDate, BigDecimal markupRate,
                                Long suggestedPrice) {
}
