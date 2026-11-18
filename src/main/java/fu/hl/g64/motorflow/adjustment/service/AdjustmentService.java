package fu.hl.g64.motorflow.adjustment.service;

import fu.hl.g64.motorflow.adjustment.dto.AdjustmentDetail;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentPreview;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentSearch;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentSummary;
import fu.hl.g64.motorflow.adjustment.dto.CorrectionRequest;
import fu.hl.g64.motorflow.adjustment.dto.RejectAdjustmentRequest;
import fu.hl.g64.motorflow.adjustment.dto.StockLossRequest;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/**
 * Adjustment requests (FE-10): stock loss or damage reported by a Branch Manager or Owner (UC-83), followed in a list
 * (UC-82) and decided by Owner (UC-84). Nothing changes stock or debt until Owner approves and the request is applied.
 */
public interface AdjustmentService {

    /** UC-83 steps 2-5: what the write-off would be; nothing is saved. */
    AdjustmentPreview previewStockLoss(AuthenticatedUser user, StockLossRequest request);

    /** UC-83 steps 6-7: saves the request PENDING with its evidence and tells Owner. */
    AdjustmentDetail submitStockLoss(AuthenticatedUser user, StockLossRequest request);

    /** UC-81 steps 2-5: the correction's effects on stock and debt; nothing is saved. */
    AdjustmentPreview previewCorrection(AuthenticatedUser user, CorrectionRequest request);

    /** UC-81 steps 6-7: saves the correction PENDING, linked to the original, which stays as it is. */
    AdjustmentDetail submitCorrection(AuthenticatedUser user, CorrectionRequest request);

    /**
     * Review 17/11 R-07 (SRS 11.2.2 "Lưu nháp"): saves a stock loss as a draft after the checks of the preview;
     * evidence is optional, nobody is told.
     */
    AdjustmentDetail saveStockLossDraft(AuthenticatedUser user, StockLossRequest request);

    /** Replaces the whole of the caller's draft. MSG253 for anyone else's; MSG281 once it is no draft. */
    AdjustmentDetail updateDraft(AuthenticatedUser user, long id, StockLossRequest request);

    /** Sends the caller's draft to Owner exactly as {@link #submitStockLoss} would: checks again, MSG255, PENDING. */
    AdjustmentDetail submitDraft(AuthenticatedUser user, long id);

    /** Throws the caller's draft away with its lines and files. MSG302 when a transfer loss is linked to it. */
    void deleteDraft(AuthenticatedUser user, long id);

    /** UC-82: a Branch Manager's store, or every store for Owner; newest submission first. */
    PageResponse<AdjustmentSummary> list(AuthenticatedUser user, AdjustmentSearch search, int page, int size);

    /** UC-84 step 2. */
    AdjustmentDetail get(AuthenticatedUser user, long id);

    /**
     * UC-84 steps 3-9 in two transactions: the approval is recorded (APPROVED), then applied (APPLIED). When the
     * application fails nothing of it stays and the request remains APPROVED for {@link #apply}; the failure is
     * thrown. Approving an APPLIED request shows it again without repeating anything.
     */
    AdjustmentDetail approve(AuthenticatedUser owner, long id);

    /** UC-84 Step 1_Approved but unapplied: applies the existing approval without another decision. */
    AdjustmentDetail apply(AuthenticatedUser owner, long id);

    /** UC-84 Step 3_Reject request. */
    AdjustmentDetail reject(AuthenticatedUser owner, long id, RejectAdjustmentRequest request);

    /** The requester takes back a request still PENDING (plan 6.6). */
    AdjustmentDetail withdraw(AuthenticatedUser user, long id);
}
