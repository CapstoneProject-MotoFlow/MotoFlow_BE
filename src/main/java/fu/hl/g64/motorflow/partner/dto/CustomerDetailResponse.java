package fu.hl.g64.motorflow.partner.dto;

import fu.hl.g64.motorflow.partner.entity.enums.EventType;
import fu.hl.g64.motorflow.receivable.entity.enums.OriginType;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;
import fu.hl.g64.motorflow.sales.entity.enums.CompletionType;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-28: the customer with credit history, unsettled debts, newest orders and payments, and waiting unmet demand.
 * A Branch Manager sees only the documents of their own store, Owner and the central-warehouse Branch Manager see every
 * store (BR-03); the debt totals in {@code customer} always cover the whole chain (BR-53). Orders and payments hold at
 * most the newest {@code CustomerService.HISTORY_SIZE} rows; the counts cover all of them.
 */
public record CustomerDetailResponse(CustomerResponse customer, List<CreditEvent> creditHistory, List<DebtRow> debts,
                                     List<OrderRow> orders, long orderCount, List<PaymentRow> payments, long paymentCount,
                                     List<UnmetDemandRow> unmetDemands) {

    public record CreditEvent(EventType eventType, String reason, String decidedBy, LocalDateTime decidedAt) {
    }

    /** {@code salesOrderId} is null for an opening balance. */
    public record DebtRow(long id, OriginType originType, long storeId, String storeName, Long salesOrderId,
                          long originalAmount, long remainingAmount, LocalDateTime recognizedAt) {
    }

    /** {@code total} comes from the current version: delivered quantities once confirmed, requested ones before. */
    public record OrderRow(long id, long storeId, String storeName, SalesOrderStatus status, CompletionType completionType,
                           long total, LocalDateTime createdAt, LocalDateTime completedAt) {
    }

    public record PaymentRow(long id, PaymentType paymentType, Long salesOrderId, Long receivableId, long amount,
                             PaymentMethod paymentMethod, PaymentStatus status, LocalDateTime recordedAt) {
    }

    public record UnmetDemandRow(long id, long salesOrderId, long productId, String sku, String partName, int unmetQty,
                                 LocalDateTime recordedAt) {
    }
}
