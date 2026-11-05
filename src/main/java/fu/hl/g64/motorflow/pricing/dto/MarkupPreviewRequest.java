package fu.hl.g64.motorflow.pricing.dto;

import fu.hl.g64.motorflow.pricing.dto.MarkupRulesRequest.CategoryMarkup;
import fu.hl.g64.motorflow.pricing.dto.MarkupRulesRequest.ProductMarkup;
import fu.hl.g64.motorflow.pricing.entity.enums.RoundingRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-101 preview: suggested prices of sample parts under a markup configuration that is not saved. Without
 * {@code productIds} the samples are the active parts received from a supplier most recently.
 */
public record MarkupPreviewRequest(
        @NotNull(message = "Vui lòng chọn mức làm tròn.")
        RoundingRule roundingRule,

        List<@NotNull @Valid CategoryMarkup> categories,

        List<@NotNull @Valid ProductMarkup> products,

        @Size(max = 50, message = "Xem trước tối đa 50 phụ tùng.")
        List<@NotNull Long> productIds) {

    public MarkupPreviewRequest {
        categories = categories == null ? List.of() : categories;
        products = products == null ? List.of() : products;
        productIds = productIds == null ? List.of() : productIds;
    }
}
