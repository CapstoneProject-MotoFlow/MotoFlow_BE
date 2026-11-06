package fu.hl.g64.motorflow.pricing.dto;

import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;
import fu.hl.g64.motorflow.pricing.entity.enums.RoundingRule;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** UC-101: the markup configuration in force and the one waiting for its date (each null if none). */
public record MarkupRulesResponse(MarkupConfiguration current, MarkupConfiguration scheduled) {

    /** One saved configuration: its date, rounding rule, the rates by category and by part, and who saved it. */
    public record MarkupConfiguration(LocalDateTime effectiveFrom, RoundingRule roundingRule,
                                      List<CategoryRate> categories, List<ProductRate> products, long setById,
                                      String setByName, LocalDateTime setAt) {
    }

    public record CategoryRate(long categoryId, String categoryName, VehicleType vehicleType, Long parentId,
                               BigDecimal markupRate) {
    }

    public record ProductRate(long productId, String sku, String partName, BigDecimal markupRate) {
    }
}
