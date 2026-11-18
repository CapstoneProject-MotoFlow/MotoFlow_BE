package fu.hl.g64.motorflow.report.service;

import fu.hl.g64.motorflow.report.dto.GrossMarginReport;
import fu.hl.g64.motorflow.report.dto.MarginGrouping;
import fu.hl.g64.motorflow.report.dto.ReportGrouping;
import fu.hl.g64.motorflow.report.dto.SalesCollectionReport;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.time.LocalDate;

public interface ReportService {

    /** UC-87: sales and money collected of a period, by day or month and by store. */
    SalesCollectionReport salesCollection(AuthenticatedUser user, Long storeId, LocalDate from, LocalDate to,
                                          ReportGrouping groupBy);

    /** UC-102 (Owner): revenue, cost of goods sold and gross margin by store, category or part. */
    GrossMarginReport grossMargin(AuthenticatedUser user, Long storeId, Long categoryId, Long productId,
                                  LocalDate from, LocalDate to, MarginGrouping groupBy);

    /** UC-89 (Owner): the period's figures as an Excel workbook for the accountant. */
    ExportedFile accountingExport(AuthenticatedUser user, Long storeId, LocalDate from, LocalDate to);

    record ExportedFile(String fileName, byte[] content) {
    }
}
