package fu.hl.g64.motorflow.sales.controller;

import fu.hl.g64.motorflow.sales.dto.SalesSummaryResponse;
import fu.hl.g64.motorflow.sales.service.SalesSummaryService;
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

@Tag(name = "Sales summary", description = "Revenue, completed orders, money collected and credit sales of a period, "
        + "per store, for the dashboards. Computed each time; nothing is saved. Amounts are in VND.")
@RestController
@RequestMapping("/api/v1/sales-orders/summary")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class SalesSummaryController {

    private final SalesSummaryService salesSummary;

    @Operation(summary = "Sales figures from one day to another, both included, today by default and at most 366 days. "
            + "Owner: every store or the one chosen; Branch Manager: own store")
    @GetMapping
    public SalesSummaryResponse summary(@AuthenticationPrincipal AuthenticatedUser user,
                                        @RequestParam(required = false) Long storeId,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return salesSummary.summary(user, storeId, from, to);
    }
}
