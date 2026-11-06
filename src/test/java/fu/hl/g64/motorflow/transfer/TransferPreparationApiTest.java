package fu.hl.g64.motorflow.transfer;

import fu.hl.g64.motorflow.sales.SalesOrderTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-57 for an outgoing transfer: the preparer records what they took out of the source warehouse. */
class TransferPreparationApiTest extends SalesOrderTestData {

    private long transfer;

    @BeforeEach
    void createTransfer() {
        transfer = id("INSERT INTO transfer_order (source_store_id, dest_store_id, created_by) VALUES (?, ?, ?) "
                + "RETURNING id", store, otherStore, manager.getId());
        jdbc.update("INSERT INTO transfer_order_line (transfer_order_id, product_id, planned_qty) VALUES (?, ?, 4), "
                + "(?, ?, 2)", transfer, pad, transfer, oil);
    }

    @Test
    void preparerRecordsAndCorrectsPreparedQuantities() throws Exception {
        preparation(staffToken).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        assign(managerToken, "DISPATCH_PREPARATION", transfer, staff).andExpect(status().isOk());

        // Step 2: where the parts are kept and which lots to take first (BR-75): the lot expiring soonest, then the
        // oldest lot without an expiry date; the expired lot is never offered.
        preparation(staffToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_DISPATCH"))
                .andExpect(jsonPath("$.sourceStoreName").value("Gia Lâm"))
                .andExpect(jsonPath("$.destStoreName").value("Long Biên"))
                .andExpect(jsonPath("$.preparer.id").value(staff.getId()))
                .andExpect(jsonPath("$.preparer.taskStatus").value("ASSIGNED"))
                .andExpect(jsonPath("$.canRecord").value(true))
                .andExpect(jsonPath("$.lines[0].sku").value("MA-PHANH-01"))
                .andExpect(jsonPath("$.lines[0].barcodes[0]").value("8930001"))
                .andExpect(jsonPath("$.lines[0].storageArea").value("Kệ A"))
                .andExpect(jsonPath("$.lines[0].binLocation").value("A-01"))
                .andExpect(jsonPath("$.lines[0].plannedQty").value(4))
                .andExpect(jsonPath("$.lines[0].preparedQty").doesNotExist())
                .andExpect(jsonPath("$.lines[0].sellableQty").value(8))
                .andExpect(jsonPath("$.lines[0].pickFirst[0].lotId").value(padSoon))
                .andExpect(jsonPath("$.lines[0].pickFirst[0].takeQty").value(3))
                .andExpect(jsonPath("$.lines[0].pickFirst[1].lotId").value(padNoExpiry))
                .andExpect(jsonPath("$.lines[0].pickFirst[1].takeQty").value(1));
        preparation(otherStaffToken).andExpect(status().isForbidden());
        preparation(otherManagerToken).andExpect(status().isForbidden());
        preparation(ownerToken).andExpect(status().isOk()).andExpect(jsonPath("$.canRecord").value(false));

        record(otherStaffToken, pad, 4, null, oil, 2, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        record(staffToken, pad, 5, null, oil, 2, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG176"))
                .andExpect(jsonPath("$.message")
                        .value("Số đã chuẩn bị của phụ tùng MA-PHANH-01 không được lớn hơn số cần chuyển 4."));
        record(staffToken, pad, 4, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG167"))
                .andExpect(jsonPath("$.message").value("Vui lòng ghi số lượng cho mọi phụ tùng của chứng từ, còn thiếu NHOT-01."));
        record(staffToken, pad, 4, null, lamp, 1, null, oil, 2, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG166"))
                .andExpect(jsonPath("$.message").value("Phụ tùng ở dòng 2 không có trong chứng từ hoặc đã có ở dòng trên."));
        record(staffToken, pad, -1, null, oil, 2, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_order_line WHERE transfer_order_id = ? "
                + "AND prepared_qty IS NOT NULL", Integer.class, transfer)).isZero();

        record(staffToken, pad, 3, "Hộp cuối bị móp", oil, 2, "không dùng")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preparer.taskStatus").value("RESULT_SUBMITTED"))
                .andExpect(jsonPath("$.canRecord").value(true))
                .andExpect(jsonPath("$.lines[0].preparedQty").value(3))
                .andExpect(jsonPath("$.lines[0].shortQty").value(1))
                .andExpect(jsonPath("$.lines[0].shortReason").value("Hộp cuối bị móp"))
                .andExpect(jsonPath("$.lines[0].pickFirst.length()").value(1))
                .andExpect(jsonPath("$.lines[1].shortQty").value(0))
                .andExpect(jsonPath("$.lines[1].shortReason").doesNotExist());
        assertThat(prepared(pad)).isEqualTo(3);
        assertThat(prepared(oil)).isEqualTo(2);
        assertThat(taskStatus("DISPATCH_PREPARATION", transfer)).isEqualTo("RESULT_SUBMITTED");
        assertThat(notifications(manager, "TRANSFER_DISPATCH_PREPARED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_type = 'TRANSFER_ORDER' "
                + "AND entity_id = ? AND action = 'TRANSFER_ORDER_DISPATCH_PREPARED'", Integer.class, transfer))
                .isEqualTo(1);

        // The preparer may correct the result until the dispatch is confirmed.
        record(staffToken, pad, 4, null, oil, 2, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].shortQty").value(0))
                .andExpect(jsonPath("$.lines[0].shortReason").doesNotExist());
        assertThat(prepared(pad)).isEqualTo(4);
        assertThat(notifications(manager, "TRANSFER_DISPATCH_PREPARED")).isEqualTo(2);

        jdbc.update("UPDATE transfer_order SET status = 'IN_TRANSIT' WHERE id = ?", transfer);
        record(staffToken, pad, 3, null, oil, 2, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG29"));
        preparation(managerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.canRecord").value(false))
                .andExpect(jsonPath("$.lines[0].pickFirst.length()").value(0));
    }

    @Test
    void unknownTransferIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/transfer-orders/999999/preparation").header(HttpHeaders.AUTHORIZATION, managerToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG175"))
                .andExpect(jsonPath("$.message").value("Không tìm thấy phiếu chuyển kho."));
    }

    private ResultActions preparation(String token) throws Exception {
        return mvc.perform(get("/api/v1/transfer-orders/" + transfer + "/preparation")
                .header(HttpHeaders.AUTHORIZATION, token));
    }

    /** [productId, preparedQty, reason, ...] */
    private ResultActions record(String token, Object... lines) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < lines.length; i += 3) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", lines[i]);
            row.put("preparedQty", lines[i + 1]);
            row.put("shortReason", lines[i + 2]);
            rows.add(row);
        }
        return mvc.perform(put("/api/v1/transfer-orders/" + transfer + "/prepared-lines")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("lines", rows))));
    }

    private int prepared(long product) {
        return jdbc.queryForObject("SELECT prepared_qty FROM transfer_order_line WHERE transfer_order_id = ? "
                + "AND product_id = ?", Integer.class, transfer, product);
    }
}
