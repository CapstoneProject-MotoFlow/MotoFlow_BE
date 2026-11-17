package fu.hl.g64.motorflow.receiving;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PM 17/11 Q6: the "Giá nhập lần trước" column of the PO form takes the last confirmed price from the same supplier. */
class LastPurchasePriceApiTest extends PurchaseOrderTestData {

    @Test
    void theLastConfirmedPriceFromTheSameSupplierOnly() throws Exception {
        long older = created(order(honda, giaLam, TODAY, null, line(pad, 5, 180_000), line(oil, 10, 90_000)));
        long newer = created(order(honda, giaLam, TODAY, null, line(pad, 5, 200_000), line(lamp, 2, 300_000)));
        long fromYamaha = created(order(yamaha, giaLam, TODAY, null, line(filter, 4, 50_000)));
        receipt(older, LocalDateTime.of(2026, 10, 1, 9, 0), "CONFIRMED", pad, 180_000, 5, oil, 90_000, 10);
        receipt(newer, LocalDateTime.of(2026, 10, 5, 9, 0), "CONFIRMED", pad, 200_000, 5, lamp, 300_000, 0);
        // Not confirmed yet: does not count.
        receipt(newer, null, "AWAITING_CONFIRMATION", pad, 210_000, 5, oil, 95_000, 3);
        receipt(fromYamaha, LocalDateTime.of(2026, 10, 6, 9, 0), "CONFIRMED", filter, 50_000, 4, filter, 50_000, 0);

        prices(ownerToken, honda, pad + "," + oil + "," + lamp + "," + filter + "," + discontinued)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].productId", contains((int) pad, (int) oil)))
                .andExpect(jsonPath("$[*].lastPrice", contains(200_000, 90_000)))
                .andExpect(jsonPath("$[*].purchaseOrderId", contains((int) newer, (int) older)))
                .andExpect(jsonPath("$[0].receivedAt").value("2026-10-05T09:00:00"));
        // The filter was only ever bought from Yamaha.
        prices(ownerToken, yamaha, String.valueOf(filter))
                .andExpect(jsonPath("$[*].lastPrice", contains(50_000)));
        prices(ownerToken, inactiveSupplier, String.valueOf(pad)).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void onlyOwnerSeesPurchasePrices() throws Exception {
        prices(managerToken, honda, String.valueOf(pad)).andExpect(status().isForbidden());
        prices(staffToken, honda, String.valueOf(pad)).andExpect(status().isForbidden());
    }

    private ResultActions prices(String token, long supplier, String productIds) throws Exception {
        return mvc.perform(get("/api/v1/purchase-orders/last-prices").param("supplierId", String.valueOf(supplier))
                .param("productIds", productIds).header(HttpHeaders.AUTHORIZATION, token));
    }

    /** A supplier delivery with two lines, inserted as it is after inspection. */
    private void receipt(long order, LocalDateTime confirmedAt, String status, long product1, long price1, int qty1,
                         long product2, long price2, int qty2) {
        LocalDateTime submitted = LocalDateTime.of(2026, 10, 1, 8, 0);
        long id = jdbc.queryForObject("""
                INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, arrived_at, status, recorded_by,
                    submitted_at, confirmed_by, confirmed_at)
                VALUES ('SUPPLIER_DELIVERY', ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, order, submitted,
                status, staff.getId(), submitted, confirmedAt == null ? null : manager.getId(), confirmedAt);
        line(id, product1, price1, qty1);
        if (product2 != product1) {
            line(id, product2, price2, qty2);
        }
    }

    private void line(long receipt, long product, long price, int qty) {
        jdbc.update("""
                INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, document_qty, delivered_qty,
                    passed_qty, unit_price) VALUES (?, ?, ?, ?, ?, ?)""", receipt, product, qty, qty, qty, price);
    }
}
