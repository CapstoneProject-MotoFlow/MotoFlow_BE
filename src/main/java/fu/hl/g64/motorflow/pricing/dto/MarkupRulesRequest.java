package fu.hl.g64.motorflow.pricing.dto;

import fu.hl.g64.motorflow.pricing.entity.enums.RoundingRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * UC-101: the whole markup configuration from {@code effectiveDate} (today or later). It replaces the rules in force
 * from that day: a category or part left out has no rate of its own any more. A part takes its own rate, otherwise
 * the rate of its category or of the nearest parent category that has one (BR-69).
 */
public record MarkupRulesRequest(
        @NotNull(message = "Vui lòng chọn ngày áp dụng.")
        LocalDate effectiveDate,

        @NotNull(message = "Vui lòng chọn mức làm tròn.")
        RoundingRule roundingRule,

        List<@NotNull @Valid CategoryMarkup> categories,

        List<@NotNull @Valid ProductMarkup> products) {

    public MarkupRulesRequest {
        categories = categories == null ? List.of() : categories;
        products = products == null ? List.of() : products;
    }

    /** The markup rate of one part category, in percent. */
    public record CategoryMarkup(
            @NotNull(message = "Vui lòng chọn nhóm hàng.")
            Long categoryId,

            @NotNull(message = "Vui lòng nhập tỷ lệ cộng thêm.")
            @DecimalMin(value = "0", message = "Tỷ lệ cộng thêm phải từ 0 đến 999,99%.")
            @DecimalMax(value = "999.99", message = "Tỷ lệ cộng thêm phải từ 0 đến 999,99%.")
            @Digits(integer = 3, fraction = 2, message = "Tỷ lệ cộng thêm có tối đa 2 chữ số thập phân.")
            BigDecimal markupRate) {
    }

    /** The markup rate of one part, used instead of its category's rate. */
    public record ProductMarkup(
            @NotNull(message = "Vui lòng chọn phụ tùng.")
            Long productId,

            @NotNull(message = "Vui lòng nhập tỷ lệ cộng thêm.")
            @DecimalMin(value = "0", message = "Tỷ lệ cộng thêm phải từ 0 đến 999,99%.")
            @DecimalMax(value = "999.99", message = "Tỷ lệ cộng thêm phải từ 0 đến 999,99%.")
            @Digits(integer = 3, fraction = 2, message = "Tỷ lệ cộng thêm có tối đa 2 chữ số thập phân.")
            BigDecimal markupRate) {
    }
}
