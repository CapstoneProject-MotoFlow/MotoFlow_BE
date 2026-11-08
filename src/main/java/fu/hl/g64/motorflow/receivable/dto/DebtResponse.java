package fu.hl.g64.motorflow.receivable.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.receivable.entity.enums.OriginType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One receivable (UC-65), amounts in VND (BR-53): {@code remainingAmount} = {@code originalAmount} +
 * {@code correctionAmount} (approved document corrections) − {@code collectedAmount} (verified debt payments).
 * {@code salesOrderId} is null for an opening debt; {@code note} and {@code evidence} are an opening debt's explanation
 * and documents. {@code overdue}: something is still owed after the due date; it is only highlighted (BR-53).
 */
public record DebtResponse(long id, OriginType originType, long customerId, long storeId, String storeName,
                           Long salesOrderId, long originalAmount, long correctionAmount, long collectedAmount,
                           long remainingAmount, DebtStatus status, LocalDate dueDate, boolean overdue,
                           LocalDateTime recognizedAt, String recognizedBy, LocalDateTime settledAt, String note,
                           List<AttachmentResponse> evidence) {
}
