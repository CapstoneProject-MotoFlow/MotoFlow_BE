package fu.hl.g64.motorflow.report.controller;

import fu.hl.g64.motorflow.report.dto.GrossMarginReport;
import fu.hl.g64.motorflow.report.dto.MarginGrouping;
import fu.hl.g64.motorflow.report.dto.ReportGrouping;
import fu.hl.g64.motorflow.report.dto.SalesCollectionReport;
import fu.hl.g64.motorflow.report.service.ReportService;
import fu.hl.g64.motorflow.report.service.ReportService.ExportedFile;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@Tag(name = "Reports", description = "FE-11: sales and collection (UC-87), gross margin (UC-102, Owner) and the "
        + "accountant's Excel export (UC-89, Owner). Computed each time; nothing is saved. Amounts in VND. The period "
        + "is from one day to another, both included, the current month by default and at most 366 days (MSG264).")
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ReportService reports;

    @Operation(summary = "Sales and money collected by day or month and by store (UC-87). Owner: every store or the "
            + "one chosen; Branch Manager: own store")
    @GetMapping("/sales-collection")
    @PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER')")
    public SalesCollectionReport salesCollection(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @RequestParam(required = false) Long storeId,
                                                 @RequestParam(required = false)
                                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam(required = false)
                                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                 @RequestParam(required = false) ReportGrouping groupBy) {
        return reports.salesCollection(user, storeId, from, to, groupBy);
    }

    @Operation(summary = "Revenue, cost of goods sold and gross margin by store, category or part (UC-102)")
    @GetMapping("/gross-margin")
    @PreAuthorize("hasRole('OWNER')")
    public GrossMarginReport grossMargin(@AuthenticationPrincipal AuthenticatedUser user,
                                         @RequestParam(required = false) Long storeId,
                                         @RequestParam(required = false) Long categoryId,
                                         @RequestParam(required = false) Long productId,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) MarginGrouping groupBy) {
        return reports.grossMargin(user, storeId, categoryId, productId, from, to, groupBy);
    }

    @Operation(summary = "The period's sales, purchases, payments and adjustments as an .xlsx file (UC-89)")
    @GetMapping("/accounting-export")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<byte[]> accountingExport(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @RequestParam(required = false) Long storeId,
                                                   @RequestParam(required = false)
                                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false)
                                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ExportedFile file = reports.accountingExport(user, storeId, from, to);
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }
}
