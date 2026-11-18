package fu.hl.g64.motorflow.inventory.controller;

import fu.hl.g64.motorflow.inventory.dto.InventoryOperationsResponse;
import fu.hl.g64.motorflow.inventory.service.InventoryOperationsReportService;
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
import java.time.YearMonth;

@Tag(name = "Inventory operations report", description = "UC-88 View Inventory Operations Report")
@RestController
@RequestMapping("/api/v1/reports/inventory-operations")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
@RequiredArgsConstructor
public class InventoryOperationsReportController {

    private final InventoryOperationsReportService report;

    @Operation(summary = "Opening, in, out and closing of each part in each warehouse for a month (month=yyyy-MM, "
            + "this month by default) or from one day to another (from and to, both included, at most 366 days, "
            + "MSG264), with in and out split by source: supplier receipts, supplier returns, sales, "
            + "transfers, adjustments, stock counts, opening balance. Owner: every warehouse or storeId, with "
            + "values; Branch Manager: own warehouse, quantities only (BR-72). Optional q (SKU or part name) and "
            + "categoryId (with subcategories)")
    @GetMapping
    public InventoryOperationsResponse report(@AuthenticationPrincipal AuthenticatedUser user,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(required = false) Long storeId,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(required = false) Long categoryId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return report.report(user, month, from, to, storeId, q, categoryId, page, size);
    }
}
