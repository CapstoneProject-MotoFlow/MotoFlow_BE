package fu.hl.g64.motorflow.common.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/** One page of a list. {@code page} starts at 0. */
public record PageResponse<T>(List<T> items, long totalItems, int page, int size) {

    public static <T> PageResponse<T> of(Page<?> page, List<T> items) {
        return new PageResponse<>(items, page.getTotalElements(), page.getNumber(), page.getSize());
    }
}
