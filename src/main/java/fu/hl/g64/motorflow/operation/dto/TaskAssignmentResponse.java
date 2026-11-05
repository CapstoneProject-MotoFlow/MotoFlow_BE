package fu.hl.g64.motorflow.operation.dto;

import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-86 step 2: who holds the document's task now, who it can be given to, and every earlier assignment.
 * When {@code canAssign} is false, {@code blockedCode} and {@code blockedMessage} say why (MSG39 once the result
 * is in, MSG121 for a finished or cancelled document), so the page can show it before the Branch Manager chooses.
 *
 * @param current       the open task (ASSIGNED or RESULT_SUBMITTED), or null when nobody holds it
 * @param eligibleStaff active Staff of the document's store
 * @param history       every assignment of the document, newest first, including the current one
 */
public record TaskAssignmentResponse(TaskType taskType, long documentId, long storeId, boolean canAssign,
                                     String blockedCode, String blockedMessage, TaskRow current,
                                     List<StaffOption> eligibleStaff, List<TaskRow> history) {

    /** One assignment. {@code resultSubmittedAt} is set while the result waits for confirmation. */
    public record TaskRow(long id, long staffId, String staffName, WarehouseTaskStatus status, long assignedById,
                          String assignedByName, LocalDateTime assignedAt, LocalDateTime resultSubmittedAt) {
    }

    /** A Staff member the task can go to; {@code openTasks} is how many tasks of this type they have not recorded yet. */
    public record StaffOption(long id, String username, String fullName, long openTasks) {
    }
}
