package fu.hl.g64.motorflow.operation.dto;

import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-86 step 2: who holds the document's task now, who it can be given to, and every earlier assignment.
 * When {@code canAssign} is false, {@code blockedCode} and {@code blockedMessage} say why (MSG39 once the result
 * is in, MSG121 for a finished or cancelled document), so the page can show it before the Branch Manager chooses.
 * {@code noticeCode} is MSG40 while the task is held by the Branch Manager who is looking at it.
 *
 * @param current           the open task (ASSIGNED or RESULT_SUBMITTED), or null when nobody holds it
 * @param eligibleAssignees active Staff of the document's store by name, then the Branch Manager themself
 * @param history           every assignment of the document, newest first, including the current one
 */
public record TaskAssignmentResponse(TaskType taskType, long documentId, long storeId, boolean canAssign,
                                     String blockedCode, String blockedMessage, String noticeCode,
                                     String noticeMessage, TaskRow current, List<AssigneeOption> eligibleAssignees,
                                     List<TaskRow> history) {

    /**
     * One assignment. {@code selfAssigned}: a Branch Manager took the task themself. {@code resultSubmittedAt} is set
     * while the result waits for confirmation.
     */
    public record TaskRow(long id, long assigneeId, String assigneeName, boolean selfAssigned,
                          WarehouseTaskStatus status, long assignedById, String assignedByName,
                          LocalDateTime assignedAt, LocalDateTime resultSubmittedAt) {
    }

    /**
     * Someone the task can go to. {@code self} marks the Branch Manager's own entry; {@code openTasks} is how many
     * tasks of this type the person holds and has not recorded yet.
     */
    public record AssigneeOption(long id, String username, String fullName, boolean self, long openTasks) {
    }
}
