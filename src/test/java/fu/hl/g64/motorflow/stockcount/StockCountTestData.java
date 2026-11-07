package fu.hl.g64.motorflow.stockcount;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountRequestStatus;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Shared data of the stock count tests (UC-90 to UC-95, UC-105). The clock starts on 13/10/2026 at 08:00 Vietnam
 * time and the October 2026 period is OPEN.
 *
 * <p>Warehouse "Gia Lâm" ({@link #store}) holds, all AVAILABLE:
 * <ul>
 *     <li>{@link #oil} (no expiry, storage area "Kệ A"): {@link #oilLot1} received 01/10 with 5, {@link #oilLot2}
 *     received 05/10 with 7; moving average 85,000;</li>
 *     <li>{@link #filter} (storage area "Kệ B"): {@link #filterLotA} expiring 31/01/2027 with 4, {@link #filterLotB}
 *     expiring 30/06/2027 with 3, and {@link #filterLotPlain} with no expiry received 02/10 with 2; moving average
 *     50,000;</li>
 *     <li>{@link #pad}: no stock at all.</li>
 * </ul>
 * "Long Biên" ({@link #otherStore}) is ACTIVE with its own Branch Manager; "Đông Anh" ({@link #inactiveStore}) is
 * INACTIVE.
 *
 * <p>Seed any state directly with {@link #seedRequest} and {@link #count}, then call only the endpoint under test.
 */
public abstract class StockCountTestData extends ApiTestUsers {

    protected static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    protected long store;
    protected long otherStore;
    protected long inactiveStore;

    protected UserAccount owner;
    protected UserAccount manager;
    /** Second Branch Manager of {@link #store}, who can confirm what {@link #manager} counted (BR-26). */
    protected UserAccount manager2;
    protected UserAccount otherManager;
    protected UserAccount staff;
    protected UserAccount staff2;

    protected String ownerToken;
    protected String managerToken;
    protected String manager2Token;
    protected String otherManagerToken;
    protected String staffToken;
    protected String staff2Token;

    /** October 2026, OPEN. */
    protected long period;

    protected long oil;
    protected long filter;
    protected long pad;
    protected long oilLot1;
    protected long oilLot2;
    protected long filterLotA;
    protected long filterLotB;
    protected long filterLotPlain;

    /** The completed opening count the seeded lots come from (lots other than receipts need a count). */
    private long openingCount;

    @BeforeEach
    void createStockCountData() throws Exception {
        resetDatabaseAndClock();
        store = createStore("Gia Lâm");
        otherStore = createStore("Long Biên");
        inactiveStore = id("INSERT INTO store (store_name, address, status) VALUES ('Đông Anh', 'Hà Nội', 'INACTIVE') "
                + "RETURNING id");
        owner = createUser("owner", Role.OWNER, null);
        manager = createUser("bm", Role.BRANCH_MANAGER, store);
        manager2 = createUser("bm2", Role.BRANCH_MANAGER, store);
        otherManager = createUser("bm.lb", Role.BRANCH_MANAGER, otherStore);
        staff = createUser("staff", Role.STAFF, store);
        staff2 = createUser("staff2", Role.STAFF, store);
        signInAll();

        period = period(TODAY, true);
        long category = id("INSERT INTO part_category (category_name, vehicle_type) VALUES ('Phụ tùng', 'MOTORCYCLE') "
                + "RETURNING id");
        oil = part("NHOT-01", "Nhớt 10W40", category, 85_000);
        filter = part("LOC-01", "Lọc gió", category, 50_000);
        pad = part("MA-PHANH-01", "Má phanh trước", category, null);
        openingCount = id("INSERT INTO stock_count (store_id, count_type, count_date, scope_type, status, created_by) "
                + "VALUES (?, 'OPENING', '2026-09-30', 'WHOLE', 'COMPLETED', ?) RETURNING id", store, owner.getId());
        oilLot1 = lot(store, oil, LocalDate.of(2026, 10, 1), null, 5, 80_000, "Kệ A");
        oilLot2 = lot(store, oil, LocalDate.of(2026, 10, 5), null, 7, 90_000, "Kệ A");
        filterLotA = lot(store, filter, LocalDate.of(2026, 10, 3), LocalDate.of(2027, 1, 31), 4, 50_000, "Kệ B");
        filterLotB = lot(store, filter, LocalDate.of(2026, 10, 4), LocalDate.of(2027, 6, 30), 3, 50_000, "Kệ B");
        filterLotPlain = lot(store, filter, LocalDate.of(2026, 10, 2), null, 2, 50_000, "Kệ B");
    }

    protected void signInAll() throws Exception {
        ownerToken = signIn("owner");
        managerToken = signIn("bm");
        manager2Token = signIn("bm2");
        otherManagerToken = signIn("bm.lb");
        staffToken = signIn("staff");
        staff2Token = signIn("staff2");
    }

    /** Moves the clock to 08:00 Vietnam time of {@code day} and signs everyone in again, as access tokens expire. */
    protected void today(LocalDate day) throws Exception {
        clock.set(Instant.parse(day + "T01:00:00Z"));
        signInAll();
    }

    // ---------------------------------------------------------------------------------------------- seeding

    /** The inventory period of {@code day}'s month, OPEN or CLOSED. */
    protected long period(LocalDate day, boolean open) {
        LocalDate month = day.withDayOfMonth(1);
        return id("INSERT INTO inventory_period (period_month, from_date, to_date, status, closed_at) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id", month, month, month.plusMonths(1).minusDays(1),
                open ? "OPEN" : "CLOSED", open ? null : LocalDateTime.now(clock));
    }

    protected void closePeriod(long periodId) {
        jdbc.update("UPDATE inventory_period SET status = 'CLOSED', closed_at = ? WHERE id = ?",
                LocalDateTime.now(clock), periodId);
    }

    protected long part(String sku, String name, long category, Integer movingAvgCost) {
        return id("INSERT INTO product (sku, part_name, category_id, base_unit, moving_avg_cost, created_by) "
                + "VALUES (?, ?, ?, 'cái', ?, ?) RETURNING id", sku, name, category, movingAvgCost, owner.getId());
    }

    /**
     * A lot holding {@code qty} AVAILABLE in the warehouse, with the warehouse's stock balance raised to match;
     * {@code storageArea} is set on the part's balance in that warehouse.
     */
    protected long lot(long storeId, long product, LocalDate received, LocalDate expiry, int qty, long price,
                       String storageArea) {
        long lot = id("INSERT INTO lot (product_id, source_type, stock_count_id, store_id, received_date, "
                + "purchase_unit_price, expiry_date) VALUES (?, 'OPENING', ?, ?, ?, ?, ?) RETURNING id",
                product, openingCount, storeId, received, price, expiry);
        jdbc.update("INSERT INTO lot_balance (lot_id, store_id, stock_status, quantity) VALUES (?, ?, 'AVAILABLE', ?)",
                lot, storeId, qty);
        jdbc.update("INSERT INTO stock_balance (store_id, product_id, available_qty, storage_area) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (store_id, product_id) DO UPDATE SET available_qty = stock_balance.available_qty "
                + "+ excluded.available_qty, storage_area = excluded.storage_area", storeId, product, qty, storageArea);
        return lot;
    }

    /**
     * Takes {@code qty} AVAILABLE out of a lot, as a sale between taking the book quantity and approving would
     * (BR-79). Changes balances only, no movement.
     */
    protected void sellFromLot(long lotId, int qty) {
        jdbc.update("UPDATE lot_balance SET quantity = quantity - ? WHERE lot_id = ? AND stock_status = 'AVAILABLE'",
                qty, lotId);
        jdbc.update("UPDATE stock_balance b SET available_qty = available_qty - ? FROM lot l "
                + "WHERE l.id = ? AND b.store_id = l.store_id AND b.product_id = l.product_id", qty, lotId);
    }

    /** An Owner request to {@code storeId}, requested now; {@code productIds} only for PRODUCTS. */
    protected long seedRequest(long storeId, ScopeType scope, String scopeNote, StockCountRequestStatus status,
                               Long... productIds) {
        long request = id("INSERT INTO stock_count_request (store_id, scope_type, scope_note, reason, status, "
                        + "requested_by, requested_at, cancelled_at) VALUES (?, ?, ?, 'Kiểm định kỳ', ?, ?, ?, ?) "
                        + "RETURNING id", storeId, scope.name(), scopeNote, status.name(), owner.getId(),
                LocalDateTime.now(clock), status == StockCountRequestStatus.CANCELLED ? LocalDateTime.now(clock) : null);
        for (Long product : productIds) {
            jdbc.update("INSERT INTO stock_count_request_product (stock_count_request_id, product_id) VALUES (?, ?)",
                    request, product);
        }
        return request;
    }

    /** A count of {@link #store} in {@code status}; see {@link CountSeed} for the defaults. */
    protected CountSeed count(StockCountStatus status) {
        return new CountSeed(status);
    }

    /** A line to seed: part, lot (null for the line of the lots without expiry), book and counted quantity. */
    protected record SeedLine(long productId, Long lotId, LocalDate expiryDate, int bookQty, Integer countedQty,
                              String reason, boolean recountRequested, String recountReason, Integer previousBookQty,
                              Integer previousCountedQty) {

        public static SeedLine of(long productId, Long lotId, LocalDate expiryDate, int bookQty, Integer countedQty) {
            return new SeedLine(productId, lotId, expiryDate, bookQty, countedQty, null, false, null, null, null);
        }

        /** The Branch Manager's reason for the difference. */
        public SeedLine because(String why) {
            return new SeedLine(productId, lotId, expiryDate, bookQty, countedQty, why, recountRequested,
                    recountReason, previousBookQty, previousCountedQty);
        }

        /** Sent back for a recount. */
        public SeedLine recount(String why) {
            return new SeedLine(productId, lotId, expiryDate, bookQty, countedQty, reason, true, why,
                    previousBookQty, previousCountedQty);
        }

        /** Counted again after a recount: the earlier book and counted quantities. */
        public SeedLine previously(int book, Integer counted) {
            return new SeedLine(productId, lotId, expiryDate, bookQty, countedQty, reason, recountRequested,
                    recountReason, book, counted);
        }
    }

    /** Line of {@link #oil}, which has no expiry: its lots share one line with no lot. */
    protected SeedLine oilLine(int book, Integer counted) {
        return SeedLine.of(oil, null, null, book, counted);
    }

    /** Line of {@link #filterLotA}. */
    protected SeedLine filterLotALine(int book, Integer counted) {
        return SeedLine.of(filter, filterLotA, LocalDate.of(2027, 1, 31), book, counted);
    }

    /** Line of {@link #filterLotB}. */
    protected SeedLine filterLotBLine(int book, Integer counted) {
        return SeedLine.of(filter, filterLotB, LocalDate.of(2027, 6, 30), book, counted);
    }

    /** Line of {@link #filter}'s lot without expiry ({@link #filterLotPlain}). */
    protected SeedLine filterPlainLine(int book, Integer counted) {
        return SeedLine.of(filter, null, null, book, counted);
    }

    /**
     * Builder of a seeded count. Defaults: {@link #store}, CYCLE, WHOLE, counted {@link #TODAY} in {@link #period},
     * created by {@link #manager} now; counting started now unless DRAFT; confirmed by {@link #manager} now when
     * AWAITING_APPROVAL or COMPLETED; a cancellation reason when CANCELLED.
     */
    protected final class CountSeed {
        private final StockCountStatus status;
        private long storeId = store;
        private CountType type = CountType.CYCLE;
        private ScopeType scope = ScopeType.WHOLE;
        private String scopeNote;
        private LocalDate countDate = TODAY;
        private long periodId = period;
        private Long requestId;
        private boolean started;
        private boolean confirmed;
        private final List<SeedLine> lines = new ArrayList<>();
        private Long taskAssignee;
        private WarehouseTaskStatus taskStatus;

        private CountSeed(StockCountStatus status) {
            this.status = status;
            this.started = status != StockCountStatus.DRAFT;
            this.confirmed = status == StockCountStatus.AWAITING_APPROVAL || status == StockCountStatus.COMPLETED;
        }

        public CountSeed store(long id) {
            storeId = id;
            return this;
        }

        public CountSeed type(CountType countType) {
            type = countType;
            return this;
        }

        public CountSeed scope(ScopeType scopeType, String note) {
            scope = scopeType;
            scopeNote = note;
            return this;
        }

        /** Count date and its period. */
        public CountSeed on(LocalDate date, long inventoryPeriodId) {
            countDate = date;
            periodId = inventoryPeriodId;
            return this;
        }

        public CountSeed request(long stockCountRequestId) {
            requestId = stockCountRequestId;
            return this;
        }

        /** Lines came back for a recount and the counter has not started again: no counting start time. */
        public CountSeed awaitingRestart() {
            started = false;
            return this;
        }

        /** Sent to the Owner once already (an Owner recount keeps confirmedAt, SC-14). */
        public CountSeed confirmedOnce() {
            confirmed = true;
            return this;
        }

        public CountSeed line(SeedLine line) {
            lines.add(line);
            return this;
        }

        /** The counting task, given by {@link #manager}; RESULT_SUBMITTED and DONE also get a submission time. */
        public CountSeed task(UserAccount assignee, WarehouseTaskStatus taskStatus) {
            taskAssignee = assignee.getId();
            this.taskStatus = taskStatus;
            return this;
        }

        /** Inserts the count, its lines and task. @return the count id */
        public long insert() {
            LocalDateTime now = LocalDateTime.now(clock);
            long count = id("INSERT INTO stock_count (store_id, inventory_period_id, stock_count_request_id, count_type, "
                            + "count_date, scope_type, scope_note, status, created_by, created_at, counting_started_at, "
                            + "confirmed_by, confirmed_at, cancelled_by, cancelled_at, cancellation_reason) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                    storeId, type == CountType.OPENING ? null : periodId, requestId, type.name(), countDate,
                    scope.name(), scopeNote, status.name(), manager.getId(), now, started ? now : null,
                    confirmed ? manager.getId() : null, confirmed ? now : null,
                    status == StockCountStatus.CANCELLED ? manager.getId() : null,
                    status == StockCountStatus.CANCELLED ? now : null,
                    status == StockCountStatus.CANCELLED ? "Hủy thử" : null);
            for (SeedLine line : lines) {
                jdbc.update("INSERT INTO stock_count_line (stock_count_id, product_id, lot_id, expiry_date, book_qty, "
                                + "counted_qty, counted_by, counted_at, reason, recount_requested, recount_reason, "
                                + "previous_book_qty, previous_counted_qty) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        count, line.productId(), line.lotId(), line.expiryDate(), line.bookQty(), line.countedQty(),
                        line.countedQty() == null ? null : taskAssigneeOr(staff.getId()),
                        line.countedQty() == null ? null : now, line.reason(), line.recountRequested(),
                        line.recountReason(), line.previousBookQty(), line.previousCountedQty());
            }
            if (taskAssignee != null) {
                boolean submitted = taskStatus == WarehouseTaskStatus.RESULT_SUBMITTED
                        || taskStatus == WarehouseTaskStatus.DONE;
                jdbc.update("INSERT INTO warehouse_task (task_type, stock_count_id, assigned_to, assigned_by, "
                                + "assigned_at, status, result_submitted_at) VALUES ('COUNTING', ?, ?, ?, ?, ?, ?)",
                        count, taskAssignee, manager.getId(), now, taskStatus.name(), submitted ? now : null);
            }
            return count;
        }

        private long taskAssigneeOr(long fallback) {
            return taskAssignee == null ? fallback : taskAssignee;
        }
    }

    // ---------------------------------------------------------------------------------------------- requests

    protected ResultActions getAs(String token, String url) throws Exception {
        return mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, token));
    }

    protected ResultActions postAs(String token, String url, Object body) throws Exception {
        return mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "" : json(body)));
    }

    protected ResultActions putAs(String token, String url, Object body) throws Exception {
        return mvc.perform(put(url).header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    // ---------------------------------------------------------------------------------------------- reading back

    protected long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    protected String countStatus(long count) {
        return jdbc.queryForObject("SELECT status FROM stock_count WHERE id = ?", String.class, count);
    }

    protected String requestStatus(long request) {
        return jdbc.queryForObject("SELECT status FROM stock_count_request WHERE id = ?", String.class, request);
    }

    /** The count's line ids by part, then expiry (no lot last), then id: the order of the detail response. */
    protected List<Long> lineIds(long count) {
        return jdbc.queryForList("SELECT id FROM stock_count_line WHERE stock_count_id = ? "
                + "ORDER BY product_id, expiry_date NULLS LAST, id", Long.class, count);
    }

    /** AVAILABLE quantity of a lot in its warehouse. */
    protected int availableOfLot(long lotId) {
        return jdbc.queryForObject("SELECT coalesce(sum(quantity), 0) FROM lot_balance WHERE lot_id = ? "
                + "AND stock_status = 'AVAILABLE'", Integer.class, lotId);
    }

    /** AVAILABLE quantity of a part in a warehouse (stock_balance). */
    protected int availableOf(long storeId, long product) {
        return jdbc.queryForObject("SELECT coalesce(max(available_qty), 0) FROM stock_balance WHERE store_id = ? "
                + "AND product_id = ?", Integer.class, storeId, product);
    }

    /** The count's stock movements: lot_id, quantity_change, inventory_period_id, occurred_at, movement_type. */
    protected List<Map<String, Object>> movementsOf(long count) {
        return jdbc.queryForList("SELECT lot_id, quantity_change, inventory_period_id, occurred_at, movement_type "
                + "FROM stock_movement WHERE stock_count_id = ? ORDER BY id", count);
    }

    /** The counting task statuses of a count, oldest first. */
    protected List<String> taskStatuses(long count) {
        return jdbc.queryForList("SELECT status FROM warehouse_task WHERE stock_count_id = ? ORDER BY id",
                String.class, count);
    }

    /** [notification_type, ref_type, ref_id] of what a user received, oldest first. */
    protected List<List<Object>> notificationsOf(UserAccount user) {
        return jdbc.queryForList("SELECT notification_type, ref_type, ref_id FROM notification WHERE recipient_id = ? "
                + "ORDER BY id", user.getId()).stream()
                .map(row -> Arrays.asList(row.get("notification_type"), row.get("ref_type"), row.get("ref_id")))
                .toList();
    }

    /** The signed-in user as a controller would see them, for calling services directly. */
    protected static AuthenticatedUser principal(UserAccount user) {
        return new AuthenticatedUser(user.getId(), 0, user.getUsername(), user.getFullName(), user.getRole(),
                user.getStoreId(), Set.of(), false);
    }
}
