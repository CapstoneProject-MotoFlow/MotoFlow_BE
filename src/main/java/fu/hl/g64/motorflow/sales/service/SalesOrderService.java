package fu.hl.g64.motorflow.sales.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.sales.dto.ConfirmSalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.PickingRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail;
import fu.hl.g64.motorflow.sales.dto.SalesOrderPreview;
import fu.hl.g64.motorflow.sales.dto.SalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderSearch;
import fu.hl.g64.motorflow.sales.dto.SalesOrderSummary;
import fu.hl.g64.motorflow.sales.dto.SalesOrderUpdateRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderVersion;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.util.List;

/**
 * FE-07 Sales Order Management up to confirmation: UC-52 list, UC-53 create, UC-54 detail, UC-55 update, UC-57 record
 * picked quantities, UC-58 confirm. The seller is the Branch Manager of the order's store or Owner (MSG05 otherwise);
 * the picker is whoever the Branch Manager gave the picking task to (UC-86, BR-32). Nothing here changes stock or
 * receivables (BR-33, BR-38): payment, credit, completion and cancellation come with UC-56, UC-60 and UC-61.
 *
 * <p>Every change of customer, parts, quantities, price type, discount or delivered quantities saves a new version
 * of the order, priced again from the retail price in effect, whose price and credit decision must be confirmed
 * again (BR-36, BR-18). Versions live in audit_log (see {@code SalesOrderHistory}).
 */
public interface SalesOrderService {

    /** UC-52: newest first. Staff see the orders they are picking, without totals. */
    PageResponse<SalesOrderSummary> list(AuthenticatedUser user, SalesOrderSearch search, int page, int size);

    /** UC-54. MSG156 if there is no such order, MSG05 outside the user's scope. */
    SalesOrderDetail get(AuthenticatedUser user, long id);

    /** Every version of the order as it was saved, oldest first (UC-55 step 8). Sellers only. */
    List<SalesOrderVersion> versions(AuthenticatedUser seller, long id);

    /** UC-53 step 5: prices, totals, threshold and stock of the order being filled in; nothing is saved. */
    SalesOrderPreview preview(AuthenticatedUser seller, SalesOrderRequest request);

    /**
     * UC-53: saves version 1, waiting for picking. MSG157 Owner without a store, MSG46 store not active, MSG74 unknown
     * customer, MSG158 a part on two lines, MSG57 unknown part, MSG159 discontinued part (BR-07), MSG160 no retail
     * price, MSG161 a discount on a retail order, MSG162 or MSG163 the discount threshold (BR-16). Missing stock does
     * not stop the order (step 5).
     */
    SalesOrderDetail create(AuthenticatedUser seller, SalesOrderRequest request);

    /**
     * UC-55: saves a new version unless nothing changed. Parts or requested quantities changed after picking send
     * the order back to its picker; any change drops the confirmation and credit decision. MSG164 once completed or
     * cancelled, MSG165 if the seller did not open the current version; otherwise as {@link #create}, except that a
     * discontinued part already on the order may stay.
     */
    SalesOrderDetail update(AuthenticatedUser seller, long id, SalesOrderUpdateRequest request);

    /**
     * UC-57: the picker records the picked quantity of every line and sends it; they may correct it until the order
     * is confirmed. MSG05 unless they hold the task, MSG29 once confirmed, MSG164 once completed or cancelled, MSG165
     * for a version with other parts or quantities, MSG166 or MSG167 lines that do not match the order, MSG168 more
     * than requested.
     */
    SalesOrderDetail recordPicking(AuthenticatedUser picker, long id, PickingRequest request);

    /**
     * UC-58: sets the delivered quantities from the picked ones and confirms price and delivery for the version;
     * delivering less than requested saves a new version first. A confirmed order can be confirmed again with other
     * delivered quantities. MSG174 before picking, MSG164, MSG165, MSG166, MSG167, MSG169 more than picked, MSG170
     * nothing delivered, MSG162 or MSG163 the threshold, MSG171 not enough unexpired stock (BR-76), MSG172 or MSG173
     * the minimum margin (BR-88), MSG124 the seller picked the goods themself (BR-26).
     */
    SalesOrderDetail confirm(AuthenticatedUser seller, long id, ConfirmSalesOrderRequest request);
}
