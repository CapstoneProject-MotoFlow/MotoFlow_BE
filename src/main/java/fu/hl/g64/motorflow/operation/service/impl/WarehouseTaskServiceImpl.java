package fu.hl.g64.motorflow.operation.service.impl;

import fu.hl.g64.motorflow.receiving.helper.PurchaseOrderCode;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.repository.StockCountRepository;
import fu.hl.g64.motorflow.operation.dto.AssignTaskRequest;
import fu.hl.g64.motorflow.operation.dto.ReceivingTaskResponse;
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
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderLineRepository;
import fu.hl.g64.motorflow.receiving.helper.PurchaseOrderReceiving;
import fu.hl.g64.motorflow.partner.entity.Supplier;
import fu.hl.g64.motorflow.partner.repository.SupplierRepository;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.transfer.helper.TransferReconciliation;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderLineRepository;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
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
import java.util.HashMap;
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
    private final PurchaseOrderLineRepository purchaseOrderLines;
    private final TransferOrderLineRepository transferOrderLines;
    private final SupplierRepository suppliers;
    private final StoreRepository stores;
    private final PurchaseOrderReceiving purchaseOrderReceiving;
    private final TransferReconciliation transferReconciliation;
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
        if (type == TaskType.PICKING) {
            // UC-56: a cancellation running at the same time finishes first, so no picker is given a cancelled order.
            salesOrders.findByIdForUpdate(documentId);
        }
        Document document = requireManagedDocument(manager, type, documentId);
        Optional<WarehouseTask> open = tasks.findOpenForUpdate(type, documentId);
        String blocked = blockedCode(document, open);
        if (blocked != null) {
            throw new BusinessException(HttpStatus.CONFLICT, blocked);
        }
        UserAccount assignee = requireEligibleAssignee(manager, type, request.assigneeId(), document.storeId());
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
            tasks.saveAndFlush(WarehouseTask.assign(type, documentId, document.storeId(), assignee.getId(),
                    manager.id(), LocalDateTime.now(clock)));
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

    @Transactional(readOnly = true)
    @Override
    public boolean holds(AuthenticatedUser user, TaskType type, long documentId) {
        return tasks.findHistory(type, documentId).stream().anyMatch(task ->
                task.getStatus() == WarehouseTaskStatus.ASSIGNED && task.getAssignedTo() == user.id());
    }

    // BR-32
    @Transactional
    @Override
    public void submitResult(AuthenticatedUser user, TaskType type, long documentId) {
        heldTask(user, type, documentId).submitResult(LocalDateTime.now(clock));
    }

    // UC-57
    @Transactional
    @Override
    public void reopenOwnResult(AuthenticatedUser user, TaskType type, long documentId) {
        tasks.findOpenForUpdate(type, documentId)
                .filter(task -> task.getStatus() == WarehouseTaskStatus.RESULT_SUBMITTED
                        && task.getAssignedTo() == user.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN, "MSG05"))
                .reopen();
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
        Optional<WarehouseTask> open = tasks.findOpenForUpdate(type, documentId);
        // Only a sales order is confirmed again after a change (UC-58); its picking task is then already done.
        WarehouseTask task = open
                .or(() -> type != TaskType.PICKING ? Optional.empty()
                        : tasks.findHistory(type, documentId).stream().findFirst()
                        .filter(latest -> latest.getStatus() == WarehouseTaskStatus.DONE))
                .orElseThrow(() -> new IllegalStateException("no " + type + " result to confirm for " + documentId));
        if (task.getAssignedTo() == confirmer.id()) {
            // K1, BR-26: a Branch Manager who took the task may confirm it; the Owner is told. Picking too since PM
            // 19/11: a Branch Manager selling alone picks the goods themself and confirms the order.
            // The Owner confirming their own picking has nobody above to tell.
            if (open.isPresent() && confirmer.role() != Role.OWNER) {
                notifications.notifyOwners("TASK_SELF_CONFIRMED",
                        "Branch Manager %s đã tự xác nhận kết quả %s do chính mình thực hiện."
                                .formatted(confirmer.fullName(), describe(type, documentId)),
                        refType(type), documentId);
            }
        }
        if (open.isPresent()) {
            task.complete();
        }
    }

    @Transactional
    @Override
    public void cancelForDocument(TaskType type, long documentId) {
        tasks.findOpenForUpdate(type, documentId).ifPresent(WarehouseTask::cancel);
    }

    // Lecturer feedback 12/11: whoever records the goods receipt note inspects it.
    @Transactional
    @Override
    public void handOverToInspection(AuthenticatedUser receiver, TaskType receivingType, long documentId,
                                     long receiptId) {
        WarehouseTask receiving = heldTask(receiver, receivingType, documentId);
        receiving.finishReceiving();
        // The new receipt has no task yet; the Branch Manager who gave the receiving gave its inspection too. The
        // receiver knows, so nobody is notified.
        tasks.saveAndFlush(WarehouseTask.assign(TaskType.INSPECTION, receiptId, receiving.getStoreId(), receiver.id(),
                receiving.getAssignedBy(), LocalDateTime.now(clock)));
    }

    @Transactional(readOnly = true)
    @Override
    public List<ReceivingTaskResponse> receivingAssignedToMe(AuthenticatedUser user, TaskType type) {
        if (type == null || !type.isReceiving()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG02");
        }
        List<WarehouseTask> held = tasks.findByAssignedToAndTaskTypeAndStatusOrderByAssignedAtAscIdAsc(user.id(), type,
                WarehouseTaskStatus.ASSIGNED);
        Map<Long, String> names = users.findAllById(held.stream().map(WarehouseTask::getAssignedBy)
                .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        // One query per kind of data for the whole list, not per task.
        Set<Long> documentIds = held.stream().map(task -> documentOf(task, type)).collect(Collectors.toSet());
        Map<Long, String> partners = new HashMap<>();
        Map<Long, Long> lineCounts = new HashMap<>();
        if (!documentIds.isEmpty() && type == TaskType.SUPPLIER_RECEIVING) {
            Map<Long, Long> supplierOf = purchaseOrders.findAllById(documentIds).stream()
                    .collect(Collectors.toMap(PurchaseOrder::getId, PurchaseOrder::getSupplierId));
            Map<Long, String> supplierNames = suppliers.findAllById(new HashSet<>(supplierOf.values())).stream()
                    .collect(Collectors.toMap(Supplier::getId, Supplier::getSupplierName));
            supplierOf.forEach((order, supplier) -> partners.put(order, supplierNames.get(supplier)));
            purchaseOrderLines.summarizeByRequest(documentIds)
                    .forEach(row -> lineCounts.put((Long) row[0], (Long) row[1]));
        } else if (!documentIds.isEmpty()) {
            Map<Long, Long> sourceOf = transferOrders.findAllById(documentIds).stream()
                    .collect(Collectors.toMap(TransferOrder::getId, TransferOrder::getSourceStoreId));
            Map<Long, String> storeNames = stores.findAllById(new HashSet<>(sourceOf.values())).stream()
                    .collect(Collectors.toMap(Store::getId, Store::getStoreName));
            sourceOf.forEach((order, source) -> partners.put(order, storeNames.get(source)));
            transferOrderLines.countLinesByOrder(documentIds)
                    .forEach(row -> lineCounts.put((Long) row[0], (Long) row[1]));
        }
        return held.stream().map(task -> {
            long documentId = documentOf(task, type);
            return new ReceivingTaskResponse(task.getId(), type, documentId, "#" + documentId,
                    partners.get(documentId), lineCounts.getOrDefault(documentId, 0L), task.getAssignedAt(),
                    task.getAssignedBy(), names.get(task.getAssignedBy()));
        }).toList();
    }

    private static long documentOf(WarehouseTask task, TaskType type) {
        return type == TaskType.SUPPLIER_RECEIVING ? task.getPurchaseOrderId() : task.getTransferOrderId();
    }

    /**
     * The document, if the user manages it: the Branch Manager of its store, or the Owner for the picking of any
     * store's sales order (GVHD 20/11); every other task type stays with the store's Branch Manager. MSG05
     * otherwise, MSG120 if the document does not exist.
     */
    private Document requireManagedDocument(AuthenticatedUser manager, TaskType type, long documentId) {
        Document document = find(type, documentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG120"));
        // GVHD 20/11: the Owner sells at every store like its Branch Manager, so they manage its picking too.
        boolean owner = manager.role() == Role.OWNER && type == TaskType.PICKING;
        boolean branchManager = manager.role() == Role.BRANCH_MANAGER
                && Objects.equals(manager.storeId(), document.storeId());
        if (!owner && !branchManager) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return document;
    }

    private Optional<Document> find(TaskType type, long documentId) {
        return switch (type) {
            // A draft is not a sales order until submitted (PM 13/11): it is never picked, so it is not found.
            case PICKING -> salesOrders.findById(documentId).filter(order -> !order.isDraft())
                    .map(order -> new Document(order.getStoreId(),
                    switch (order.getStatus()) {
                        case DRAFT -> throw new IllegalStateException("draft " + documentId);
                        // GVHD 20/11: a waiting order is not picked until it is released to picking.
                        case AWAITING_STOCK -> throw new BusinessException(HttpStatus.CONFLICT, "MSG308");
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
            // Open while a delivery or a replacement delivery can still be recorded (UC-106, same rule as the PO
            // detail's canCreateReceipt / canCreateReplacement).
            // A draft PO is not a PO yet (PM 17/11): nothing to receive.
            case SUPPLIER_RECEIVING -> purchaseOrders.findById(documentId).filter(order -> !order.isDraft())
                    .map(order -> new Document(
                    order.getStoreId(), purchaseOrderReceiving.of(order).any() ? Stage.OPEN : Stage.FINISHED));
            // Received at the destination; open while goods can arrive (same rule as the arrival itself).
            case TRANSFER_RECEIVING -> transferOrders.findById(documentId).map(order -> new Document(
                    order.getDestStoreId(),
                    transferReconciliation.arrival(order).blockedCode() == null ? Stage.OPEN : Stage.FINISHED));
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
     * UC-86 step 4: an active Staff account of the document's store, or the manager themself (a Branch Manager
     * gets MSG40; the Owner may take picking too, GVHD 20/11). They already passed {@link #requireManagedDocument}.
     */
    private UserAccount requireEligibleAssignee(AuthenticatedUser manager, TaskType type, long assigneeId,
                                                long storeId) {
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
        // MSG40 speaks to the Branch Manager who took the task, so only they get it: it promises the Owner is told,
        // and the Owner confirming their own picking has nobody above to tell.
        String notice = manager.role() == Role.BRANCH_MANAGER
                && open.filter(task -> task.getAssignedTo() == manager.id()).isPresent() ? "MSG40" : null;

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
            case SUPPLIER_RECEIVING -> "nhận hàng và lập phiếu nhập cho đơn nhập hàng";
            case TRANSFER_RECEIVING -> "nhận hàng điều chuyển cho lệnh";
        };
        return work + " " + (type == TaskType.SUPPLIER_RECEIVING ? PurchaseOrderCode.of(documentId) : "#" + documentId);
    }

    /** notification.ref_type of the document, so that opening the notification opens it. */
    private static String refType(TaskType type) {
        return switch (type) {
            case PICKING -> "SALES_ORDER";
            case INSPECTION -> "GOODS_RECEIPT_NOTE";
            case DISPATCH_PREPARATION -> "TRANSFER_ORDER";
            case COUNTING -> "STOCK_COUNT";
            case SUPPLIER_RECEIVING -> "PURCHASE_ORDER";
            case TRANSFER_RECEIVING -> "TRANSFER_ORDER";
        };
    }
}
