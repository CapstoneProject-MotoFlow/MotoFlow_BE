package fu.hl.g64.motorflow.receiving.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.receiving.dto.AssignedReceiptResponse;
import fu.hl.g64.motorflow.receiving.dto.ExcessDecisionRequest;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptDetail;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptRequest;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptSearch;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptSummary;
import fu.hl.g64.motorflow.receiving.dto.InspectionResultRequest;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/**
 * Goods Receipt Notes (GRN) of supplier deliveries. A Branch Manager acts on their own store's receipts only, Owner
 * on every store's (MSG05 otherwise); MSG132 for an unknown GRN.
 */
public interface GoodsReceiptService {

    /**
     * UC-39 for Staff: the supplier deliveries whose inspection the user holds and has not finished, oldest
     * assignment first. A Branch Manager who took an inspection themself (UC-86) sees it here too.
     */
    PageResponse<AssignedReceiptResponse> assignedToMe(AuthenticatedUser user, int page, int size);

    /** The supplier deliveries of the user's store (every store for Owner), latest arrival first. */
    PageResponse<GoodsReceiptSummary> list(AuthenticatedUser user, GoodsReceiptSearch search, int page, int size);

    /** One GRN; Staff sees it only while they hold, or held, its inspection. */
    GoodsReceiptDetail get(AuthenticatedUser user, long id);

    /**
     * UC-106: the receiving Branch Manager records a delivery that arrived for a PO, with one line per PO line still
     * outstanding, or with the replacement quantities of the listed discrepancy cases. MSG28 if the delivery document
     * is already recorded for the PO, MSG29 if the PO is cancelled or has nothing outstanding, MSG141 for a case with
     * no replacement left to receive, MSG138 for a document date or arrival in the future.
     */
    GoodsReceiptDetail create(AuthenticatedUser manager, long purchaseOrderId, GoodsReceiptRequest request);

    /**
     * UC-43: the assigned Staff member (or the Branch Manager who took the inspection) sends the inspection result
     * for confirmation. MSG29 once the GRN no longer waits for a result, MSG05 for anyone who does not hold the task,
     * MSG27, MSG136, MSG137, MSG140 and MSG138 for invalid lines.
     */
    GoodsReceiptDetail recordResult(AuthenticatedUser user, long id, InspectionResultRequest request);

    /**
     * UC-44: the receiving Branch Manager confirms the result. Passed goods up to what the PO (or the replacement)
     * still expects go to available stock in one new lot per line; wrong-item and damaged goods are kept in their own
     * statuses with a discrepancy case; the rest is excess waiting for Owner. MSG29 if the GRN no longer waits for
     * confirmation, MSG32 for the person who recorded the result, MSG85 for a closed period.
     */
    GoodsReceiptDetail confirm(AuthenticatedUser manager, long id);

    /** UC-44 Step 3_Return for recount: the result goes back to the person who recorded it. MSG29 as for confirm. */
    GoodsReceiptDetail returnForRecount(AuthenticatedUser manager, long id, String reason);

    /**
     * UC-45: Owner accepts or rejects the excess of every line still waiting for a decision. MSG139 for a line left
     * out, MSG29 for a line already decided or a GRN that is not confirmed, MSG136 for a line of another GRN.
     */
    GoodsReceiptDetail decideExcess(AuthenticatedUser owner, long id, ExcessDecisionRequest request);
}
