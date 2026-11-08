package fu.hl.g64.motorflow.transfer.service;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.RecountRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferReceiptResponse;
import fu.hl.g64.motorflow.transfer.dto.TransferReceiptResultRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferReturnRequest;

/**
 * UC-106, UC-43, UC-44 for transferred goods: the receiving Branch Manager records the arrival and assigns a Staff
 * member (UC-86), who records the result; another Branch Manager of the store confirms it, which moves the order's own
 * lots out of transit. Kept apart from supplier receipts, whose rules read the purchase order.
 */
public interface TransferReceiptService {

    /** UC-106: the destination Branch Manager records that goods arrived; each part expects what is still uncovered. */
    TransferReceiptResponse arrive(AuthenticatedUser user, long transferOrderId);

    /**
     * UC-76 Step 3_Return to source: a source Branch Manager records goods coming back, each part at most what is
     * still uncovered. Inspected and confirmed like any receipt; confirming puts them back at the source (BR-61).
     */
    TransferReceiptResponse arriveReturn(AuthenticatedUser user, long transferOrderId, TransferReturnRequest request);

    /** The receiving Branch Manager, the coordinators, and the Staff member who inspects it. */
    TransferReceiptResponse receipt(AuthenticatedUser user, long transferOrderId, long receiptId);

    /** UC-43: the assigned Staff member records passed and damaged per part. */
    TransferReceiptResponse recordResult(AuthenticatedUser user, long transferOrderId, long receiptId,
                                         TransferReceiptResultRequest request);

    /** UC-44 Step 3_Return for recount: a receiving Branch Manager sends the result back; nothing is posted. */
    TransferReceiptResponse returnForRecount(AuthenticatedUser user, long transferOrderId, long receiptId,
                                             RecountRequest request);

    /**
     * UC-44: a receiving Branch Manager who did not record the result moves the received goods out of transit, keeps
     * excess out of stock (BR-68) and opens a discrepancy case for what is still missing (BR-59). Sent again after it
     * succeeded, returns the receipt.
     */
    TransferReceiptResponse confirm(AuthenticatedUser user, long transferOrderId, long receiptId);
}
