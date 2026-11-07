package fu.hl.g64.motorflow.receivable.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.receivable.dto.CustomerDebtsResponse;
import fu.hl.g64.motorflow.receivable.dto.DebtPaymentPreviewRequest;
import fu.hl.g64.motorflow.receivable.dto.DebtPaymentPreviewResponse;
import fu.hl.g64.motorflow.receivable.dto.DebtPaymentRequest;
import fu.hl.g64.motorflow.receivable.dto.DebtPaymentResponse;
import fu.hl.g64.motorflow.receivable.dto.DebtResponse;
import fu.hl.g64.motorflow.receivable.dto.DebtorResponse;
import fu.hl.g64.motorflow.receivable.dto.OpeningDebtRequest;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/**
 * Customer debts (FE-08): what customers owe each store, opening debts, and debt payments. Amounts are in VND. A Branch
 * Manager sees their own store, Owner and the central-warehouse Branch Manager any store (BR-03, as on the customer
 * profile); debt payments are recorded only at the collector's own store, or at the store Owner chooses.
 */
public interface CustomerDebtService {

    /**
     * UC-64: customers who still owe the store, or any store for Owner when {@code storeId} is null, with their debt to
     * the whole chain (BR-53). Overdue debts first, then the largest. {@code q} finds by name, phone or customer
     * number; {@code overdueOnly} keeps customers with something overdue.
     */
    PageResponse<DebtorResponse> debtors(AuthenticatedUser user, Long storeId, String q, boolean overdueOnly, int page,
                                         int size);

    /** UC-65: every debt of the customer at the store, or at every store for Owner, and the debt payments made on them. */
    CustomerDebtsResponse debts(AuthenticatedUser user, long customerId, Long storeId);

    /**
     * UC-67: Owner records a debt from before MotoFlow for one open store. MSG147 when the same opening debt (amount
     * and due date) is already recorded for the customer at that store, MSG46 for a store that does not exist or is not
     * open, MSG74 for an unknown customer.
     */
    DebtResponse recordOpeningDebt(AuthenticatedUser owner, long customerId, OpeningDebtRequest request);

    /**
     * UC-66 step 4: how the amount would be allocated (BR-70); nothing is saved. MSG149 when nothing is owed at the
     * store, MSG148 for a named debt that is not still owed by this customer at this store, MSG150 for an amount above
     * what the debts still owe (BR-47).
     */
    DebtPaymentPreviewResponse previewPayment(AuthenticatedUser collector, long customerId,
                                              DebtPaymentPreviewRequest request);

    /**
     * UC-66 steps 5-8: saves one verified debt payment with its allocations and settles the debts it pays off; no
     * revenue or stock change (BR-52). Same checks as the preview, then MSG152 when the allocation differs from the
     * confirmed preview, MSG151 for a transaction reference already recorded, MSG153 for change on a bank transfer or
     * cash tendered below the amount, MSG154 for a bank transfer without its reference.
     */
    DebtPaymentResponse recordPayment(AuthenticatedUser collector, long customerId, DebtPaymentRequest request);
}
