package fu.hl.g64.motorflow.operation.service;

import fu.hl.g64.motorflow.operation.dto.AssignTaskRequest;
import fu.hl.g64.motorflow.operation.dto.ReceivingTaskResponse;
import fu.hl.g64.motorflow.operation.dto.TaskAssignmentResponse;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.util.List;

/**
 * UC-86 Assign Warehouse Staff and BR-32: only the person a Branch Manager gave the task to records its result.
 * That person is a Staff member of the store, or the Branch Manager themself (MSG40); either way someone else
 * confirms the result (BR-26). A Branch Manager takes an inspection, a dispatch preparation or a count themself only
 * while the store has another active Branch Manager to confirm it. A task belongs to a sales order (PICKING), a goods receipt note (INSPECTION),
 * a transfer order (DISPATCH_PREPARATION), a stock count (COUNTING), or is the receiving of a purchase order's goods
 * (SUPPLIER_RECEIVING) or of a transfer order's goods (TRANSFER_RECEIVING): whoever holds a receiving task records the
 * goods receipt note and then inspects it.
 *
 * <p>The features that record and confirm those results call the last five methods inside their own transaction:
 * {@link #requireAssignee} before saving recorded quantities, {@link #submitResult} when the result is sent,
 * then {@link #returnForRecount}, {@link #confirmResult} or {@link #cancelForDocument}. Their recording APIs must
 * also let a Branch Manager in, since a Branch Manager can hold the task.
 */
public interface WarehouseTaskService {

    /**
     * UC-86 step 2, for the Branch Manager of the document's store (MSG05 otherwise, MSG120 if there is no such
     * document).
     */
    TaskAssignmentResponse assignment(AuthenticatedUser manager, TaskType type, long documentId);

    /**
     * UC-86 steps 3-6: gives the task to an active Staff member of the document's store and notifies them, or lets
     * the Branch Manager take it themself (MSG40 is then in the response; MSG177 when the task is not picking and the
     * store has no other active Branch Manager, who alone could confirm the result). Giving it to someone else before the
     * result is in cancels the open task, keeps it in the history and notifies both people, except the Branch
     * Manager doing it. MSG39 once the result is in, MSG121 for a finished or cancelled document, MSG41 for a locked
     * Staff account or one at another store, MSG122 for any other account that is not Staff, MSG48 for an unknown
     * account, MSG123 when another Branch Manager assigned the same document at the same moment.
     */
    TaskAssignmentResponse assign(AuthenticatedUser manager, AssignTaskRequest request);

    /** BR-32: MSG05 unless the user holds the document's task and has not sent its result yet. */
    void requireAssignee(AuthenticatedUser user, TaskType type, long documentId);

    /** Whether the user holds the document's task and has not sent its result yet, without locking it. */
    boolean holds(AuthenticatedUser user, TaskType type, long documentId);

    /** The assignee sends the result for confirmation (checked as in {@link #requireAssignee}); from now on MSG39. */
    void submitResult(AuthenticatedUser user, TaskType type, long documentId);

    /**
     * UC-57 "Correct an unconfirmed result": the assignee takes back the result they sent while nobody has confirmed
     * it, then records and sends it again. MSG05 unless the user sent the result that waits.
     */
    void reopenOwnResult(AuthenticatedUser user, TaskType type, long documentId);

    /**
     * The result was sent back for a recount, also after it was confirmed (an Owner asking for a recount of a
     * confirmed stock count): the person who recorded it records again and can again be replaced.
     *
     * @return that person's account id
     */
    long returnForRecount(TaskType type, long documentId);

    /**
     * The result was confirmed: the task is done. BR-26: the confirmer must not be the person who recorded the
     * result, which matters when a Branch Manager took the task themself (MSG32 for a goods receipt note, MSG124
     * for the other documents). A result already confirmed can be confirmed again when the document changed without
     * a new result (a sales order whose price or delivered quantities were revised, UC-55, UC-58): the same check
     * applies and the task stays done.
     */
    void confirmResult(AuthenticatedUser confirmer, TaskType type, long documentId);

    /** The document was cancelled or can no longer use the task: its open task, if any, is cancelled too. */
    void cancelForDocument(TaskType type, long documentId);

    /**
     * The holder of a receiving task (SUPPLIER_RECEIVING of a purchase order, TRANSFER_RECEIVING of a transfer order)
     * recorded goods receipt note {@code receiptId}: the receiving task is done and its INSPECTION goes to the same
     * person, given by the Branch Manager who gave the receiving, without a notification. MSG05 unless the user holds
     * the receiving task (ASSIGNED). Call inside the transaction that saves the receipt.
     */
    void handOverToInspection(AuthenticatedUser receiver, TaskType receivingType, long documentId, long receiptId);

    /** The receiving tasks of {@code type} the user holds and has not done, oldest first (MSG02 for another type). */
    List<ReceivingTaskResponse> receivingAssignedToMe(AuthenticatedUser user, TaskType type);
}
