package fu.hl.g64.motorflow.operation.dto;

import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import jakarta.validation.constraints.NotNull;

/**
 * UC-86: give the task of one document to a Staff member of its store, or take it yourself (MSG40).
 * {@code taskType} says which kind of document {@code documentId} is: a sales order (PICKING), a goods receipt note
 * (INSPECTION), a transfer order (DISPATCH_PREPARATION) or a stock count (COUNTING).
 */
public record AssignTaskRequest(
        @NotNull(message = "Vui lòng chọn loại việc.")
        TaskType taskType,

        @NotNull(message = "Vui lòng chọn chứng từ.")
        Long documentId,

        @NotNull(message = "Vui lòng chọn người được giao.")
        Long assigneeId) {
}
