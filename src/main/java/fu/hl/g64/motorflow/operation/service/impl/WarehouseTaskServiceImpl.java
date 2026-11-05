package fu.hl.g64.motorflow.operation.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.repository.StockCountRepository;
import fu.hl.g64.motorflow.operation.dto.AssignTaskRequest;
import fu.hl.g64.motorflow.operation.dto.TaskAssignmentResponse;
import fu.hl.g64.motorflow.operation.dto.TaskAssignmentResponse.AssigneeOption;
import fu.hl.g64.motorflow.operation.dto.TaskAssignmentResponse.TaskRow;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderRepository;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class WarehouseTaskServiceImpl implements WarehouseTaskService {

    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi");

    private final WarehouseTaskRepository tasks;
    private final SalesOrderRepository salesOrders;
    private final GoodsReceiptNoteRepository receipts;
    private final PurchaseOrderRepository purchaseOrders;
    private final TransferOrderRepository transferOrders;
    private final StockCountRepository stockCounts;
    private final UserAccountRepository users;
    private final NotificationService notifications;
    private final MessageSource messages;
    private final Clock clock;

    /** Where the document stands for its warehouse task. */
    private enum Stage {
        /** Waiting for (re)assignment or for its result. */
        OPEN,
        /** The Staff member's result waits for confirmation or approval. */
        RESULT_IN,
        /** Done or cancelled. */
        FINISHED
    }

    /** {@code storeId}: the store whose Branch Manager assigns the task and whose Staff may take it. */
    private record Document(long storeId, Stage stage) {
    }

    // UC-86
    @Transactional(readOnly = true)
    @Override
    public TaskAssignmentResponse assignment(AuthenticatedUser manager, TaskType type, long documentId) {
        return view(manager, type, documentId, requireManagedDocument(manager, type, documentId));
    }

    // UC-86
    @Transactional
    @Override
    public TaskAssignmentResponse assign(AuthenticatedUser manager, AssignTaskRequest request) {
        TaskType type = request.taskType();
        long documentId = request.documentId();
        Document document = requireManagedDocument(manager, type, documentId);
        Optional<WarehouseTask> open = tasks.findOpenForUpdate(type, documentId);
        String blocked = blockedCode(document, open);
        if (blocked != null) {
            throw new BusinessException(HttpStatus.CONFLICT, blocked);
        }
        UserAccount assignee = requireEligibleAssignee(manager, request.assigneeId(), document.storeId());
        if (open.isPresent() && open.get().getAssignedTo().equals(assignee.getId())) {
            return view(manager, type, documentId, document);
        }
        String task = describe(type, documentId);
        open.ifPresent(previous -> {
            previous.cancel();
            // A document has one open task at most (ux_warehouse_task_4), so the old one must close in the database
            // before the new one is inserted.
            tasks.flush();
            if (previous.getAssignedTo() != manager.id()) {
                notifications.notify(previous.getAssignedTo(), "TASK_REASSIGNED",
                        "Việc %s đã được giao cho người khác.".formatted(task), refType(type), documentId);
            }
        });
        try {
            tasks.saveAndFlush(WarehouseTask.assign(type, documentId, assignee.getId(), manager.id(),
                    LocalDateTime.now(clock)));
        } catch (DataIntegrityViolationException e) {
            // Another Branch Manager gave the document to someone between our read and our insert.
            throw new BusinessException(HttpStatus.CONFLICT, "MSG123");
        }
        if (assignee.getId() != manager.id()) {
            notifications.notify(assignee.getId(), "TASK_ASSIGNED", "Bạn được giao %s.".formatted(task),
                    refType(type), documentId);
        }
        return view(manager, type, documentId, document);
    }

    // BR-32
    @Transactional
    @Override
    public void requireAssignee(AuthenticatedUser user, TaskType type, long documentId) {
        heldTask(user, type, documentId);
    }

    // BR-32
    @Transactional
    @Override
    public void submitResult(AuthenticatedUser user, TaskType type, long documentId) {
        heldTask(user, type, documentId).submitResult(LocalDateTime.now(clock));
    }

    @Transactional
    @Override
    public long returnForRecount(TaskType type, long documentId) {
        WarehouseTask task = tasks.findOpenForUpdate(type, documentId)
                .or(() -> tasks.findHistory(type, documentId).stream().findFirst()
                        .filter(latest -> latest.getStatus() == WarehouseTaskStatus.DONE))
                .orElseThrow(() -> new IllegalStateException("no " + type + " result to return for " + documentId));
        task.reopen();
        return task.getAssignedTo();
    }

    @Transactional
    @Override
    public void confirmResult(AuthenticatedUser confirmer, TaskType type, long documentId) {
        WarehouseTask task = tasks.findOpenForUpdate(type, documentId)
                .orElseThrow(() -> new IllegalStateException("no open " + type + " task for " + documentId));
        // BR-26: whoever recorded the result, a Branch Manager who took the task included (MSG40), cannot confirm it.
        if (task.getAssignedTo() == confirmer.id()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, type == TaskType.INSPECTION ? "MSG32" : "MSG124");
        }
        task.complete();
    }

    @Transactional
    @Override
    public void cancelForDocument(TaskType type, long documentId) {
        tasks.findOpenForUpdate(type, documentId).ifPresent(WarehouseTask::cancel);
    }

    /** The document, if the user is a Branch Manager of its store (MSG05 otherwise, MSG120 if it does not exist). */
    private Document requireManagedDocument(AuthenticatedUser manager, TaskType type, long documentId) {
        Document document = find(type, documentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG120"));
        if (manager.role() != Role.BRANCH_MANAGER || !Objects.equals(manager.storeId(), document.storeId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return document;
    }

    private Optional<Document> find(TaskType type, long documentId) {
        return switch (type) {
            case PICKING -> salesOrders.findById(documentId).map(order -> new Document(order.getStoreId(),
                    switch (order.getStatus()) {
                        case AWAITING_PICKING -> Stage.OPEN;
                        case PICKED -> Stage.RESULT_IN;
                        case CONFIRMED, COMPLETED, CANCELLED -> Stage.FINISHED;
                    }));
            case INSPECTION -> receipts.findById(documentId).map(receipt -> new Document(receivingStore(receipt),
                    switch (receipt.getStatus()) {
                        case AWAITING_INSPECTION, AWAITING_RECOUNT -> Stage.OPEN;
                        case AWAITING_CONFIRMATION -> Stage.RESULT_IN;
                        case CONFIRMED -> Stage.FINISHED;
                    }));
            // Goods are prepared at the source; once dispatched, the preparation is over.
            case DISPATCH_PREPARATION -> transferOrders.findById(documentId).map(order -> new Document(
                    order.getSourceStoreId(),
                    order.getStatus() == TransferOrderStatus.AWAITING_DISPATCH ? Stage.OPEN : Stage.FINISHED));
            // A count confirmed by the Branch Manager waits for the Owner, who may still ask for a recount (UC-95).
            case COUNTING -> stockCounts.findById(documentId).map(count -> new Document(count.getStoreId(),
                    switch (count.getStatus()) {
                        case DRAFT, COUNTING -> Stage.OPEN;
                        case AWAITING_APPROVAL -> Stage.RESULT_IN;
                        case COMPLETED, CANCELLED -> Stage.FINISHED;
                    }));
        };
    }

    /**
     * Same rule as {@link GoodsReceiptNoteRepository#RECEIVED_AT_STORE}: a supplier delivery arrives at the store of
     * its purchase order, a transfer at its destination, and goods sent back at the source.
     */
    private long receivingStore(GoodsReceiptNote receipt) {
        return switch (receipt.getReceiptType()) {
            case SUPPLIER_DELIVERY -> purchaseOrders.findById(receipt.getPurchaseOrderId()).orElseThrow().getStoreId();
            case TRANSFER_IN -> transferOrders.findById(receipt.getTransferOrderId()).orElseThrow().getDestStoreId();
            case RETURN_TO_SOURCE ->
                    transferOrders.findById(receipt.getTransferOrderId()).orElseThrow().getSourceStoreId();
        };
    }

    /** Why the task cannot be given to anyone (else) now, or null if it can. */
    private static String blockedCode(Document document, Optional<WarehouseTask> open) {
        if (document.stage() == Stage.FINISHED) {
            return "MSG121";
        }
        boolean resultIn = document.stage() == Stage.RESULT_IN
                || open.filter(task -> task.getStatus() == WarehouseTaskStatus.RESULT_SUBMITTED).isPresent();
        return resultIn ? "MSG39" : null;
    }

    /**
     * UC-86 step 4: an active Staff account of the document's store, or the Branch Manager themself (MSG40; they
     * already passed {@link #requireManagedDocument}).
     */
    private UserAccount requireEligibleAssignee(AuthenticatedUser manager, long assigneeId, long storeId) {
        UserAccount assignee = users.findById(assigneeId)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG48"));
        if (assignee.getId() == manager.id()) {
            return assignee;
        }
        if (assignee.getRole() != Role.STAFF) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG122");
        }
        if (assignee.getStatus() != AccountStatus.ACTIVE || !Objects.equals(assignee.getStoreId(), storeId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG41");
        }
        return assignee;
    }

    /** BR-32: the open task, if the user holds it and has not sent the result yet (MSG05 otherwise). */
    private WarehouseTask heldTask(AuthenticatedUser user, TaskType type, long documentId) {
        return tasks.findOpenForUpdate(type, documentId)
                .filter(task -> task.getStatus() == WarehouseTaskStatus.ASSIGNED && task.getAssignedTo() == user.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN, "MSG05"));
    }

    private TaskAssignmentResponse view(AuthenticatedUser manager, TaskType type, long documentId,
                                        Document document) {
        List<WarehouseTask> history = tasks.findHistory(type, documentId);
        Optional<WarehouseTask> open = history.stream()
                .filter(task -> task.getStatus() == WarehouseTaskStatus.ASSIGNED
                        || task.getStatus() == WarehouseTaskStatus.RESULT_SUBMITTED)
                .findFirst();
        String blocked = blockedCode(document, open);
        // MSG40 speaks to the Branch Manager who took the task, so only they get it.
        String notice = open.filter(task -> task.getAssignedTo() == manager.id()).isPresent() ? "MSG40" : null;

        Collator byName = Collator.getInstance(VIETNAMESE);
        List<UserAccount> staff = users.findByRoleAndStoreIdAndStatus(Role.STAFF, document.storeId(),
                        AccountStatus.ACTIVE).stream()
                .sorted(Comparator.comparing(UserAccount::getFullName, byName).thenComparing(UserAccount::getId))
                .toList();
        List<Long> candidates = Stream.concat(staff.stream().map(UserAccount::getId), Stream.of(manager.id()))
                .toList();
        Map<Long, Long> openTasks = tasks.countAssignedByAssignee(type, candidates).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        List<AssigneeOption> eligible = new ArrayList<>();
        staff.forEach(u -> eligible.add(new AssigneeOption(u.getId(), u.getUsername(), u.getFullName(), false,
                openTasks.getOrDefault(u.getId(), 0L))));
        eligible.add(new AssigneeOption(manager.id(), manager.username(), manager.fullName(), true,
                openTasks.getOrDefault(manager.id(), 0L)));

        Set<Long> people = new HashSet<>();
        history.forEach(task -> {
            people.add(task.getAssignedTo());
            people.add(task.getAssignedBy());
        });
        Map<Long, String> names = users.findAllById(people).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        Function<WarehouseTask, TaskRow> row = task -> new TaskRow(task.getId(), task.getAssignedTo(),
                names.get(task.getAssignedTo()), task.getAssignedTo().equals(task.getAssignedBy()), task.getStatus(),
                task.getAssignedBy(), names.get(task.getAssignedBy()), task.getAssignedAt(),
                task.getResultSubmittedAt());

        return new TaskAssignmentResponse(type, documentId, document.storeId(), blocked == null, blocked,
                text(blocked), notice, text(notice), open.map(row).orElse(null), eligible,
                history.stream().map(row).toList());
    }

    private String text(String code) {
        return code == null ? null : messages.getMessage(code, null, code, VIETNAMESE);
    }

    /** How the task reads in a notification, for example "lấy hàng cho đơn bán #12". */
    private static String describe(TaskType type, long documentId) {
        String work = switch (type) {
            case PICKING -> "lấy hàng cho đơn bán";
            case INSPECTION -> "kiểm nhận hàng cho phiếu nhận hàng";
            case DISPATCH_PREPARATION -> "chuẩn bị hàng xuất cho phiếu chuyển kho";
            case COUNTING -> "đếm hàng cho phiếu kiểm kê";
        };
        return work + " #" + documentId;
    }

    /** notification.ref_type of the document, so that opening the notification opens it. */
    private static String refType(TaskType type) {
        return switch (type) {
            case PICKING -> "SALES_ORDER";
            case INSPECTION -> "GOODS_RECEIPT_NOTE";
            case DISPATCH_PREPARATION -> "TRANSFER_ORDER";
            case COUNTING -> "STOCK_COUNT";
        };
    }
}
