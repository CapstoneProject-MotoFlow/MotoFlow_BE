package fu.hl.g64.motorflow.sales.service;

import fu.hl.g64.motorflow.sales.dto.DepositExpiryRequest;
import fu.hl.g64.motorflow.sales.dto.DepositPaymentRequest;
import fu.hl.g64.motorflow.sales.dto.DepositSplitRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail;
import fu.hl.g64.motorflow.sales.dto.SettlementResult;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/** PM 21/11: the deposit of a waiting order (BE-3, BE-4, BE-7). */
public interface SalesDepositService {

    /** Records a deposit, first or additional; a verified one counts at once. Verify and reject as any payment. */
    SettlementResult recordSplitDeposit(AuthenticatedUser seller, long orderId, DepositSplitRequest request);

    SettlementResult recordDeposit(AuthenticatedUser seller, long orderId, DepositPaymentRequest request);

    /** The store's Branch Manager moves the last day of a deposit that has not expired yet. */
    SalesOrderDetail changeExpiry(AuthenticatedUser manager, long orderId, DepositExpiryRequest request);

    /** The store's Branch Manager cancels a waiting order whose deposit expired; the store keeps the deposit left. */
    SalesOrderDetail forfeit(AuthenticatedUser manager, long orderId);
}
