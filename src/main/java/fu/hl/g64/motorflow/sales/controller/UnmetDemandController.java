package fu.hl.g64.motorflow.sales.controller;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.sales.dto.UnmetDemandRow;
import fu.hl.g64.motorflow.sales.dto.UnmetDemandSearch;
import fu.hl.g64.motorflow.sales.service.UnmetDemandService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "Unmet demand", description = "UC-59: parts customers asked for and did not receive, from completed orders "
        + "that delivered less than requested and from cancelled orders. Computed each time; nothing is saved.")
@RestController
@RequestMapping("/api/v1/unmet-demand")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class UnmetDemandController {

    private final UnmetDemandService unmetDemand;

    @Operation(summary = "Unmet demand, newest first, over the last 30 days by default (UC-59)")
    @GetMapping
    public PageResponse<UnmetDemandRow> list(@AuthenticationPrincipal AuthenticatedUser user,
                                             @RequestParam(required = false) Long storeId,
                                             @RequestParam(required = false) Long productId,
                                             @RequestParam(required = false) Long customerId,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return unmetDemand.list(user, new UnmetDemandSearch(storeId, productId, customerId, q, from, to), page, size);
    }
}
