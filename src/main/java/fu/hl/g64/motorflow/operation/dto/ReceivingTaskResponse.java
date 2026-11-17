package fu.hl.g64.motorflow.operation.dto;

import fu.hl.g64.motorflow.operation.entity.enums.TaskType;

import java.time.LocalDateTime;

/**
 * A receiving task the user holds and has not done yet: receive the goods of a purchase order (SUPPLIER_RECEIVING,
 * {@code partnerName} = supplier) or of a transfer order at its destination (TRANSFER_RECEIVING, {@code partnerName}
 * = the dispatching warehouse) and record their goods receipt note. {@code documentCode} is "#" + id (BR-01);
 * {@code lineCount}: parts on the order.
 */
public record ReceivingTaskResponse(long taskId, TaskType taskType, long documentId, String documentCode,
                                    String partnerName, long lineCount, LocalDateTime assignedAt, Long assignedById,
                                    String assignedByName) {
}
