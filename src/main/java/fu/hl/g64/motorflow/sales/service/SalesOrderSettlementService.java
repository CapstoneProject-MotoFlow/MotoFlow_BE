package fu.hl.g64.motorflow.sales.service;

import fu.hl.g64.motorflow.sales.dto.CancelSalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.CompleteSalesOrderRequest;
import fu.hl.g64.motorflow.sales.dto.OrderCreditRequest;
import fu.hl.g64.motorflow.sales.dto.OrderPaymentRequest;
import fu.hl.g64.motorflow.sales.dto.OrderRefundRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail;
import fu.hl.g64.motorflow.sales.dto.SettlementResult;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/**
 * How a sales order ends: UC-60 Record Order Payment, UC-61 Record Order Credit, UC-56 Cancel Sales Order. Only the
 * seller acts: Owner, or the Branch Manager of the order's store (MSG05). MSG156 if there is no such order, MSG164
 * once it is completed or cancelled.
 *
 * <p>Money counts only once verified (BR-46): net collected = verified payments − verified refunds (BR-49). Completion
 * re-checks the confirmed version, the discount threshold (BR-16) and the unexpired available stock (BR-31, BR-76),
 * then takes the delivered goods out lot by lot, earliest expiry first (SALE_ISSUE, BR-75), at the moving average
 * cost, and the order is COMPLETED once (BR-38). Revenue is the order total; the issue cost is in the stock
 * movements. A completion that fails changes nothing but keeps the money already recorded.
 */
public interface SalesOrderSettlementService {

    /**
     * UC-60 steps 3-9: records a payment of a confirmed order and, if it is verified and the order is now paid in full
     * with nothing unverified left, completes the order (BR-51). MSG179 unless confirmed, MSG180 nothing left to pay,
     * MSG181 more than is payable (BR-47), MSG182 tendered cash below the amount or for a bank transfer, MSG154 a bank
     * transfer without reference, MSG151 a reference already recorded (BR-45).
     */
    SettlementResult recordPayment(AuthenticatedUser seller, long orderId, OrderPaymentRequest request);

    /**
     * UC-60 Step 7_Net collection exceeds the total: gives money back before completion, at most what the order holds
     * (MSG183, BR-48). If the order is then exactly paid, it completes as {@link #recordPayment} does.
     */
    SettlementResult recordRefund(AuthenticatedUser seller, long orderId, OrderRefundRequest request);

    /**
     * BR-46: the seller verifies a payment or refund recorded unverified; it counts from now on, and the order completes
     * if it is now exactly paid. MSG191 if the order has no such row, MSG192 if it was verified or rejected already.
     */
    SettlementResult verifyPayment(AuthenticatedUser seller, long orderId, long paymentId);

    /** UC-56 Step 4_Unverified transaction: the row never counts. MSG191, MSG192 as {@link #verifyPayment}. */
    SalesOrderDetail rejectPayment(AuthenticatedUser seller, long orderId, long paymentId, String reason);

    /**
     * UC-60 Step 2_Already paid after failed completion. MSG179 unless confirmed, MSG165 another version was saved,
     * MSG184 a payment or refund waits for verification, MSG186 still payable, MSG187 overpaid (BR-50), then the
     * completion checks: MSG162 or MSG163 the threshold, MSG171 not enough unexpired stock.
     */
    SalesOrderDetail complete(AuthenticatedUser seller, long orderId, CompleteSalesOrderRequest request);

    /**
     * UC-61: puts the unpaid balance of the confirmed version on credit with its due date and completes the order,
     * creating one receivable (BR-42, BR-50). The customer row is locked, so a revocation is seen at once (BR-44).
     * MSG179, MSG165, MSG188 walk-in sale, MSG189 the customer has no credit eligibility, MSG184, MSG187, MSG180
     * nothing unpaid, MSG190 the amount is not the unpaid balance, MSG178 due date before today, then the completion
     * checks as {@link #complete}.
     */
    SalesOrderDetail recordCredit(AuthenticatedUser seller, long orderId, OrderCreditRequest request);

    /**
     * UC-56: refunds what the order still holds, if a refund is given, and cancels the unfinished order with its
     * reason (BR-39). Cancelling an order that is already cancelled shows it again. MSG164 once completed, MSG184
     * a payment or refund waits for verification, MSG183 refunding more than is held, MSG185 money still held after
     * the refund. Stock, revenue and receivables do not change; an open picking task is cancelled.
     */
    SalesOrderDetail cancel(AuthenticatedUser seller, long orderId, CancelSalesOrderRequest request);
}
