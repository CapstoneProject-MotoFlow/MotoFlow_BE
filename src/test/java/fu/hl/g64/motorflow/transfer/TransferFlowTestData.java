package fu.hl.g64.motorflow.transfer;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A transfer from the Cầu Giấy warehouse to Long Biên, coordinated by the central warehouse Branch Manager, with real
 * lots at the source. Steps of the flow go through the API so each test starts from what the previous step posted.
 */
abstract class TransferFlowTestData extends ApiTestUsers {

    protected static final String ORDERS = "/api/v1/transfer-orders";

    @Autowired
    protected InventoryPostingService posting;
    @Autowired
    protected TransactionTemplate tx;

    protected UserAccount owner;
    protected UserAccount sourceManager;
    protected UserAccount otherSourceManager;
    protected UserAccount destManager;
    protected UserAccount sourceStaff;
    protected UserAccount destStaff;
    protected long central;
    protected long source;
    protected long dest;
    protected long brake;
    protected long chain;
    protected LocalDate today;
    private long supplier;
    private long purchaseOrder;

    @BeforeEach
    void setUpStoresAndParts() {
        resetDatabaseAndClock();
        today = LocalDate.now(clock);
        central = createStore("Kho tổng");
        jdbc.update("UPDATE store SET is_central = TRUE WHERE id = ?", central);
        source = createStore("Cầu Giấy");
        dest = createStore("Long Biên");
        owner = createUser("owner", Role.OWNER, null);
        createUser("bmkt", Role.BRANCH_MANAGER, central);
        sourceManager = createUser("bmcg", Role.BRANCH_MANAGER, source);
        otherSourceManager = createUser("bmcg2", Role.BRANCH_MANAGER, source);
        destManager = createUser("bmlb", Role.BRANCH_MANAGER, dest);
        sourceStaff = createUser("nvcg", Role.STAFF, source);
        destStaff = createUser("nvlb", Role.STAFF, dest);
        long category = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Phanh', 'MOTORCYCLE') RETURNING id", Long.class);
        brake = product("MA-PHANH", "Má phanh trước", category);
        chain = product("XICH-XE", "Xích tải", category);
        supplier = jdbc.queryForObject("INSERT INTO supplier (supplier_code, supplier_name) VALUES ('NCC1', 'Honda') "
                + "RETURNING id", Long.class);
        purchaseOrder = jdbc.queryForObject("INSERT INTO purchase_order (supplier_id, store_id, created_by) "
                + "VALUES (?, ?, ?) RETURNING id", Long.class, supplier, source, owner.getId());
    }

    /** A supplier lot of {@code quantity} available units at {@code store}. */
    protected long lot(long store, long product, int quantity, long unitPrice, LocalDate expiry, String lotNo) {
        long receipt = jdbc.queryForObject("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, "
                + "delivery_document_no, arrived_at) VALUES ('SUPPLIER_DELIVERY', ?, ?, now()) RETURNING id", Long.class,
                purchaseOrder, "PX-" + System.nanoTime());
        long line = jdbc.queryForObject("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, "
                + "document_qty, delivered_qty, passed_qty) VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class, receipt,
                product, quantity, quantity, quantity);
        return tx.execute(s -> posting.receiveNewLot(new NewLotPosting(store, product, LotSourceType.RECEIPT, line,
                supplier, today.minusDays(30), unitPrice, lotNo, null, expiry, Map.of(StockStatus.AVAILABLE, quantity),
                MovementType.RECEIPT_CONFIRMED, SourceRef.goodsReceiptNote(receipt), owner.getId(), null))).getId();
    }

    /** UC-71 by the central warehouse Branch Manager: [productId, plannedQty, ...]. */
    protected long createOrder(long... lines) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < lines.length; i += 2) {
            rows.add(Map.of("productId", lines[i], "plannedQty", lines[i + 1]));
        }
        mvc.perform(post(ORDERS).header(HttpHeaders.AUTHORIZATION, signIn("bmkt"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sourceStoreId", source, "destStoreId", dest, "lines", rows))))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT max(id) FROM transfer_order", Long.class);
    }

    protected ResultActions assign(String manager, String type, long document, UserAccount assignee) throws Exception {
        return mvc.perform(post("/api/v1/warehouse-tasks").header(HttpHeaders.AUTHORIZATION, signIn(manager))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("taskType", type, "documentId", document,
                        "assigneeId", assignee.getId()))));
    }

    /** UC-57: [productId, preparedQty, ...]. */
    protected ResultActions prepare(String preparer, long order, long... lines) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < lines.length; i += 2) {
            rows.add(Map.of("productId", lines[i], "preparedQty", lines[i + 1]));
        }
        return mvc.perform(put(ORDERS + "/" + order + "/prepared-lines").header(HttpHeaders.AUTHORIZATION,
                signIn(preparer)).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("lines", rows))));
    }

    /** UC-74: [productId, dispatchedQty, ...]. */
    protected ResultActions dispatch(String manager, long order, long... lines) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < lines.length; i += 2) {
            rows.add(Map.of("productId", lines[i], "dispatchedQty", lines[i + 1]));
        }
        return mvc.perform(post(ORDERS + "/" + order + "/dispatch").header(HttpHeaders.AUTHORIZATION, signIn(manager))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("lines", rows))));
    }

    /** Created, prepared by source Staff and dispatched by the source Branch Manager: [productId, quantity, ...]. */
    protected long dispatchedOrder(long... lines) throws Exception {
        long order = createOrder(lines);
        assign("bmcg", "DISPATCH_PREPARATION", order, sourceStaff).andExpect(status().isOk());
        prepare("nvcg", order, lines).andExpect(status().isOk());
        dispatch("bmcg", order, lines).andExpect(status().isOk());
        return order;
    }

    protected ResultActions arrive(long order) throws Exception {
        return mvc.perform(post(ORDERS + "/" + order + "/receipts").header(HttpHeaders.AUTHORIZATION, signIn("bmlb")));
    }

    protected long arrive(String manager, long order) throws Exception {
        mvc.perform(post(ORDERS + "/" + order + "/receipts").header(HttpHeaders.AUTHORIZATION, signIn(manager)))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT max(id) FROM goods_receipt_note", Long.class);
    }

    /** Arrived, given to the destination Staff member and recorded: [productId, passed, damaged, ...]. */
    protected long inspected(long order, long... lines) throws Exception {
        long receipt = arrive("bmlb", order);
        assign("bmlb", "INSPECTION", receipt, destStaff).andExpect(status().isOk());
        result("nvlb", order, receipt, lines).andExpect(status().isOk());
        return receipt;
    }

    protected ResultActions result(String recorder, long order, long receipt, long... lines) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < lines.length; i += 3) {
            rows.add(Map.of("productId", lines[i], "passedQty", lines[i + 1], "damagedQty", lines[i + 2]));
        }
        return mvc.perform(put(ORDERS + "/" + order + "/receipts/" + receipt + "/result")
                .header(HttpHeaders.AUTHORIZATION, signIn(recorder)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("lines", rows))));
    }

    protected ResultActions confirm(String manager, long order, long receipt) throws Exception {
        return mvc.perform(post(ORDERS + "/" + order + "/receipts/" + receipt + "/confirm")
                .header(HttpHeaders.AUTHORIZATION, signIn(manager)));
    }

    protected ResultActions receiptView(String username, long order, long receipt) throws Exception {
        return mvc.perform(get(ORDERS + "/" + order + "/receipts/" + receipt)
                .header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    protected ResultActions view(String username, String path) throws Exception {
        return mvc.perform(get(ORDERS + path).header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    protected int stock(long store, long product, String column) {
        return jdbc.queryForObject("SELECT coalesce(max(" + column + "), 0) FROM stock_balance WHERE store_id = ? "
                + "AND product_id = ?", Integer.class, store, product);
    }

    protected int lotStock(long lot, long store, String status) {
        return jdbc.queryForObject("SELECT coalesce(max(quantity), 0) FROM lot_balance WHERE lot_id = ? "
                + "AND store_id = ? AND stock_status = ?", Integer.class, lot, store, status);
    }

    private long product(String sku, String name, long category) {
        return jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES (?, ?, ?, 'cái', ?) RETURNING id", Long.class, sku, name, category, owner.getId());
    }
}
