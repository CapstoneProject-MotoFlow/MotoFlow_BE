package fu.hl.g64.motorflow.receivable.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.receivable.entity.enums.OriginType;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A debt payment (UC-66 step 8), amounts in VND: {@code amount} went to the debts listed in {@code allocations},
 * {@code changeAmount} was given back from {@code tenderedAmount} (null when no cash was tendered). It creates no
 * revenue and moves no stock (BR-52).
 */
public record DebtPaymentResponse(long id, long customerId, long storeId, String storeName, long amount,
                                  Long tenderedAmount, Long changeAmount, PaymentMethod paymentMethod,
                                  String transactionReference, PaymentStatus status, Long recordedById,
                                  String recordedBy, LocalDateTime recordedAt, List<Allocation> allocations,
                                  List<AttachmentResponse> evidence) {

    /** {@code remainingAmount}: what is owed on that debt now, after every payment up to today. */
    public record Allocation(long receivableId, OriginType originType, Long salesOrderId, long allocatedAmount,
                             long remainingAmount) {
    }
}
