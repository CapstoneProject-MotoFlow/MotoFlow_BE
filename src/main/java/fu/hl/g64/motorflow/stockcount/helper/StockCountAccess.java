package fu.hl.g64.motorflow.stockcount.helper;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.entity.InventoryPeriod;
import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.StockCountRequest;
import fu.hl.g64.motorflow.inventory.repository.InventoryPeriodRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountRequestRepository;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Loading and permission checks shared by the stock count services (R3 4.4 Permission Matrix). Every check throws the
 * BusinessException the screen shows; the services call them in the guard order of the R4 II.1 diagrams.
 */
@Component
@RequiredArgsConstructor
public class StockCountAccess {

    /** How much of a count the user sees (UC-92). */
    public enum Viewer {
        /** Everything, including the value of differences (BR-72). */
        OWNER,
        /** Book quantity, counted quantity and difference, but no value. */
        MANAGER,
        /** The person given the counting task: parts, lots, locations and their own counts only. */
        COUNTER
    }

    private final StockCountRepository counts;
    private final StockCountRequestRepository requests;
    private final WarehouseTaskRepository tasks;
    private final InventoryPeriodRepository periods;

    /** MSG245 if there is no such count. */
    public StockCount loadCount(long id) {
        return counts.findById(id).orElseThrow(StockCountAccess::countNotFound);
    }

    /** {@link #loadCount}, row-locked: use it before changing the count. */
    public StockCount lockCount(long id) {
        return counts.findForUpdate(id).orElseThrow(StockCountAccess::countNotFound);
    }

    /** MSG246 if there is no such request. */
    public StockCountRequest loadRequest(long id) {
        return requests.findById(id).orElseThrow(StockCountAccess::requestNotFound);
    }

    /** {@link #loadRequest}, row-locked: use it before changing the request. */
    public StockCountRequest lockRequest(long id) {
        return requests.findForUpdate(id).orElseThrow(StockCountAccess::requestNotFound);
    }

    /** MSG05 unless the user is an Owner. */
    public void requireOwner(AuthenticatedUser user) {
        if (user.role() != Role.OWNER) {
            throw forbidden();
        }
    }

    /** A Branch Manager of the store, the central warehouse's included: only for their own store (R3 4.4 note 6). */
    public boolean isManagerOf(AuthenticatedUser user, long storeId) {
        return user.role() == Role.BRANCH_MANAGER && user.storeId() != null && user.storeId() == storeId;
    }

    /** MSG05 unless {@link #isManagerOf}. */
    public void requireManagerOf(AuthenticatedUser user, long storeId) {
        if (!isManagerOf(user, storeId)) {
            throw forbidden();
        }
    }

    /** The user was given the count's counting task and it was not cancelled (R3 4.4 note 3). */
    public boolean isCounter(AuthenticatedUser user, StockCount count) {
        return tasks.findHistory(TaskType.COUNTING, count.getId()).stream()
                .anyMatch(task -> task.getAssignedTo() == user.id() && task.getStatus() != WarehouseTaskStatus.CANCELLED);
    }

    /**
     * SC-9 (MSG238) unless the user holds the count's open counting task. The task is ASSIGNED while they may record
     * and RESULT_SUBMITTED once they sent the result; the caller decides what the latter allows.
     */
    public WarehouseTask requireCounter(AuthenticatedUser user, StockCount count) {
        return tasks.findHistory(TaskType.COUNTING, count.getId()).stream()
                .filter(task -> task.getStatus() == WarehouseTaskStatus.ASSIGNED
                        || task.getStatus() == WarehouseTaskStatus.RESULT_SUBMITTED)
                .filter(task -> task.getAssignedTo() == user.id())
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN, "MSG238"));
    }

    /**
     * UC-92: the Owner, a Branch Manager of the count's store, or its counter; SC-9 (MSG238) for any other Staff member
     * and MSG05 for anyone else.
     */
    public Viewer viewerOf(AuthenticatedUser user, StockCount count) {
        if (user.role() == Role.OWNER) {
            return Viewer.OWNER;
        }
        if (isManagerOf(user, count.getStoreId())) {
            return Viewer.MANAGER;
        }
        if (isCounter(user, count)) {
            return Viewer.COUNTER;
        }
        if (user.role() == Role.STAFF) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG238");
        }
        throw forbidden();
    }

    /** SC-3 (MSG232) unless the period exists and is OPEN. */
    public InventoryPeriod requireOpenPeriod(Long periodId) {
        return Optional.ofNullable(periodId).flatMap(periods::findById)
                .filter(InventoryPeriod::isOpen)
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "MSG232"));
    }

    private static BusinessException countNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "MSG245");
    }

    private static BusinessException requestNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "MSG246");
    }

    private static BusinessException forbidden() {
        return new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
    }
}
