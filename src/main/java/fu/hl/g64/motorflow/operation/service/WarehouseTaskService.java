package fu.hl.g64.motorflow.operation.service;

import fu.hl.g64.motorflow.operation.dto.AssignTaskRequest;
import fu.hl.g64.motorflow.operation.dto.TaskAssignmentResponse;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/**
 * UC-86 Assign Warehouse Staff and BR-32: only the Staff member a Branch Manager gave the task to records its result.
 * A task belongs to a sales order (PICKING), a goods receipt note (INSPECTION), a transfer order
 * (DISPATCH_PREPARATION) or a stock count (COUNTING).
 *
 * <p>The features that record and confirm those results call the last five methods inside their own transaction:
 * {@link #requireAssignee} before saving recorded quantities, {@link #submitResult} when the Staff member sends them,
 * then {@link #returnForRecount}, {@link #confirmResult} or {@link #cancelForDocument}.
 */
public interface WarehouseTaskService {

    /**
     * UC-86 step 2, for the Branch Manager of the document's store (MSG05 otherwise, MSG120 if there is no such
     * document).
     */
    TaskAssignmentResponse assignment(AuthenticatedUser manager, TaskType type, long documentId);

    /**
     * UC-86 steps 3-6: gives the task to an active Staff member of the document's store and notifies them. Giving it
     * to someone else before the result is in cancels the open task, keeps it in the history and notifies both.
     * MSG39 once the result is in, MSG121 for a finished or cancelled document, MSG41 for a locked Staff account or
     * one at another store, MSG122 for an account that is not Staff, MSG48 for an unknown account, MSG123 when
     * another Branch Manager assigned the same document at the same moment.
     */
    TaskAssignmentResponse assign(AuthenticatedUser manager, AssignTaskRequest request);

    /** BR-32: MSG05 unless the user holds the document's task and has not sent its result yet. */
    void requireAssignee(AuthenticatedUser user, TaskType type, long documentId);

    /** The assignee sends the result for confirmation (checked as in {@link #requireAssignee}); from now on MSG39. */
    void submitResult(AuthenticatedUser user, TaskType type, long documentId);

    /**
     * The result was sent back for a recount, also after it was confirmed (an Owner asking for a recount of a
     * confirmed stock count): the Staff member who recorded it records again and can again be replaced.
     *
     * @return that Staff member's account id
     */
    long returnForRecount(TaskType type, long documentId);

    /** The result was confirmed: the task is done. */
    void confirmResult(TaskType type, long documentId);

    /** The document was cancelled: its open task, if any, is cancelled too. */
    void cancelForDocument(TaskType type, long documentId);
}
