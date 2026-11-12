package fu.hl.g64.motorflow.receiving.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderRequest;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderSearch;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderSummary;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderUpdateRequest;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/**
 * Purchase Orders (PO), shown to users as "yêu cầu nhập hàng" (Owner screens: "phiếu nhập hàng"). Owner creates,
 * changes and cancels them; the Branch Manager of the receiving warehouse follows them. Only Owner sees purchase
 * prices and values (BR-72).
 */
public interface PurchaseOrderService {

    /**
     * UC-39: Owner sees the POs of every warehouse, a Branch Manager those sent to their own warehouse (MSG05 for
     * another warehouse in {@code search.storeId}). Newest first.
     */
    PageResponse<PurchaseOrderSummary> list(AuthenticatedUser user, PurchaseOrderSearch search, int page, int size);

    /**
     * UC-41: the PO with its lines, deliveries, discrepancies and history. MSG131 (404) if it does not exist, MSG05 for
     * a Branch Manager of another warehouse.
     */
    PurchaseOrderDetail get(AuthenticatedUser user, long id);

    /**
     * UC-40: saves the PO and notifies the Branch Managers of the receiving warehouse. MSG73 for an unknown supplier,
     * MSG129 for a supplier no longer traded with, MSG46 for a warehouse that does not exist or is not open, MSG20
     * (with the line number) for a quantity or price not greater than 0 or a part on two lines, MSG57 for an unknown
     * part, MSG21 for a discontinued part (BR-07).
     */
    PurchaseOrderDetail create(AuthenticatedUser owner, PurchaseOrderRequest request);

    /**
     * UC-42: replaces the lines and the delivery details of a PO, keeps the change and its reason in the history, and
     * notifies the Branch Managers of the receiving warehouse. Nothing happens when nothing changed. MSG25 once a GRN
     * has been recorded for the PO (BR-21), MSG130 for a cancelled PO, and the line checks of {@link #create}; a part
     * discontinued after it was put on the PO may stay on it.
     */
    PurchaseOrderDetail update(AuthenticatedUser owner, long id, PurchaseOrderUpdateRequest request);

    /** UC-42 Cancel PO: same conditions as {@link #update}. The reason is kept on the PO; the store is notified. */
    PurchaseOrderDetail cancel(AuthenticatedUser owner, long id, String reason);

    /**
     * UC-106 screen 6.2.1: the PO as its receiver sees it, without prices. A Branch Manager of its store, or someone
     * holding its SUPPLIER_RECEIVING task (MSG05 otherwise); MSG131 if there is no such PO.
     */
    fu.hl.g64.motorflow.receiving.dto.PurchaseOrderReceivingView receivingView(AuthenticatedUser user, long id);
}
