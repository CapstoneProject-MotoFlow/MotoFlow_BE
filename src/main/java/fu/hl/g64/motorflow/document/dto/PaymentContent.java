package fu.hl.g64.motorflow.document.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A verified payment: money for a sales order, a refund of one, or a debt payment allocated to receivables (BR-70).
 * {@code changeGiven}: cash handed back when the customer gave more.
 */
public record PaymentContent(long id, PaymentType paymentType, PaymentMethod paymentMethod, long amount,
                             Long tenderedAmount, Long changeGiven, String transactionReference, PaymentStatus status,
                             Long salesOrderId, Long customerId, String customerName, Long recordedById,
                             String recordedBy, LocalDateTime recordedAt, Long verifiedById, String verifiedBy,
                             LocalDateTime verifiedAt, List<Allocation> allocations,
                             List<AttachmentResponse> evidence) {

    /** What a debt payment paid of one receivable. */
    public record Allocation(long receivableId, Long salesOrderId, long amount) {
    }
}
