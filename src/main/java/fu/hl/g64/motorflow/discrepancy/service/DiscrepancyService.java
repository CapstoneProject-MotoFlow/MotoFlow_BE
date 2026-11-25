package fu.hl.g64.motorflow.discrepancy.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.discrepancy.dto.AcceptAsVariantRequest;
import fu.hl.g64.motorflow.discrepancy.dto.AcceptanceRequest;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyCaseResponse;
import fu.hl.g64.motorflow.discrepancy.dto.DecisionApprovalRequest;
import fu.hl.g64.motorflow.discrepancy.dto.DecisionRejectionRequest;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyHandlingRequest;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancySearch;
import fu.hl.g64.motorflow.discrepancy.dto.ReceiveWrongItemsRequest;
import fu.hl.g64.motorflow.discrepancy.dto.RejectAcceptanceRequest;
import fu.hl.g64.motorflow.discrepancy.dto.SupplierReturnRequest;
import fu.hl.g64.motorflow.discrepancy.dto.WaiveReplacementRequest;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.util.List;

/**
 * UC-46 Resolve Supplier Discrepancy. Owner works on every store's cases, a Branch Manager on their own store's
 * (MSG05 otherwise); MSG133 for an unknown case, MSG29 for a case that is already closed. A case closes by itself
 * once every obligation is settled (BR-27).
 */
public interface DiscrepancyService {

    /** Supplier discrepancy cases, newest first. */
    PageResponse<DiscrepancyCaseResponse> list(AuthenticatedUser user, DiscrepancySearch search, int page, int size);

    /** One case with what was done on it. */
    DiscrepancyCaseResponse get(AuthenticatedUser user, long id);

    /**
     * UC-46 steps 3-7: saves the quantities to return and, apart from them, to replace for cases of one receipt, and
     * moves the goods to return to awaiting return to supplier, wrong items first, then damaged goods. MSG136 for a
     * case of another receipt or named twice, MSG35 for a return above the held quantity or a replacement above the
     * replacement limit, MSG134 for a return below what was already moved, MSG193 for a replacement below the short
     * quantity or what was already replaced, waived or is on its way.
     *
     * @return every case of the receipt
     */
    List<DiscrepancyCaseResponse> saveHandling(AuthenticatedUser handler, long receiptId,
                                               DiscrepancyHandlingRequest request);

    /**
     * GVHD 24/11, Owner only: the directions Branch Managers proposed are decided and carried out as
     * {@link #saveHandling} would. MSG360 when no chosen case awaits the Owner.
     *
     * @return every case of the receipt
     */
    List<DiscrepancyCaseResponse> approveDecisions(AuthenticatedUser owner, long receiptId,
                                                   DecisionApprovalRequest request);

    /**
     * GVHD 24/11, Owner only: proposed directions are sent back with a reason (MSG356); the cases are undecided again
     * and the store's managers are told. MSG360 when no chosen case awaits the Owner.
     *
     * @return every case of the receipt
     */
    List<DiscrepancyCaseResponse> rejectDecisions(AuthenticatedUser owner, long receiptId,
                                                  DecisionRejectionRequest request);

    /**
     * UC-46 Step 1_Actual supplier return, receiving Branch Manager only: the goods leave awaiting return to supplier.
     * MSG135 for a quantity above what is still to be returned.
     */
    DiscrepancyCaseResponse recordSupplierReturn(AuthenticatedUser manager, long id, SupplierReturnRequest request);

    /**
     * UC-46 Step 3_Shortage settled without replacement, Owner only: no stock moves. MSG141 when no replacement is
     * left to waive, MSG144 for a quantity above it.
     */
    DiscrepancyCaseResponse waiveReplacement(AuthenticatedUser owner, long id, WaiveReplacementRequest request);

    /**
     * GVHD 20/11 (PR 5): the receiving Branch Manager (or Owner) asks Owner to take held damaged or wrong goods into
     * stock; Owner is told. MSG309 for more than is held and not decided of that kind.
     */
    @Deprecated(since = "GVHD 24/11: the direction ACCEPT_NO_REPLACEMENT, proposed on the receipt, replaces it")
    DiscrepancyCaseResponse requestAcceptance(AuthenticatedUser user, long id, AcceptanceRequest request);

    /** GVHD 20/11 (PR 5), Owner only: refuses the pending request (MSG29 if none); the store's managers are told. */
    @Deprecated(since = "GVHD 24/11: see rejectDecisions")
    DiscrepancyCaseResponse rejectAcceptance(AuthenticatedUser owner, long id, RejectAcceptanceRequest request);

    /**
     * GVHD 20/11 (PR 5), Owner only: dented goods come into stock as the part's "-M" variant, made the first time
     * (MSG312), at the given cost, with the given retail price (MSG311 if the variant has none). MSG309 for more than
     * is held and not decided.
     */
    DiscrepancyCaseResponse acceptAsVariant(AuthenticatedUser owner, long id, AcceptAsVariantRequest request);

    /**
     * GVHD 20/11 (PR 5), Owner only: wrong items come into stock as the part they really are (MSG57, MSG310, MSG313),
     * waiving the replacement owed for them unless {@code replacementNeeded}. MSG309 for more than is held and not
     * decided.
     */
    DiscrepancyCaseResponse receiveWrongItems(AuthenticatedUser owner, long id, ReceiveWrongItemsRequest request);
}
