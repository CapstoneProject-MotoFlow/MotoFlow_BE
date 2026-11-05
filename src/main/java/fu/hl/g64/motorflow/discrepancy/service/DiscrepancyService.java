package fu.hl.g64.motorflow.discrepancy.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyCaseResponse;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyHandlingRequest;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancySearch;
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
     * UC-46 steps 3-7: saves the quantities to return for cases of one receipt and moves those goods to awaiting
     * return to supplier, wrong items first, then damaged goods. MSG136 for a case of another receipt, MSG35 above
     * the held quantity, MSG134 below what was already moved.
     *
     * @return every case of the receipt
     */
    List<DiscrepancyCaseResponse> saveHandling(AuthenticatedUser handler, long receiptId,
                                               DiscrepancyHandlingRequest request);

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
}
