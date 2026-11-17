package fu.hl.g64.motorflow.sales.service;

import fu.hl.g64.motorflow.sales.dto.SalesSummaryResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.time.LocalDate;

/**
 * Revenue, order count, money collected and credit sales of a period, per store (FE UI fixes 12/11). Computed each
 * time from the sales orders, payments and receivables; nothing is saved.
 */
public interface SalesSummaryService {

    /**
     * Days {@code from} to {@code to}, both included, today by default and at most 366 days (MSG264). Owner sees every
     * store or the one chosen; a Branch Manager their own store (MSG05 for another).
     */
    SalesSummaryResponse summary(AuthenticatedUser user, Long storeId, LocalDate from, LocalDate to);
}
