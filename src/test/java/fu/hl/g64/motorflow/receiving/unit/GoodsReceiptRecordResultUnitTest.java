package fu.hl.g64.motorflow.receiving.unit;

import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptDetail;
import fu.hl.g64.motorflow.receiving.dto.InspectionResultRequest;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.NOW;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Report 5.1, sheet GoodsReceipt.recordResult: GoodsReceiptServiceImpl.recordResult(user, grnId, request), UTCID01-16. */
class GoodsReceiptRecordResultUnitTest extends GoodsReceiptUnitTestBase {

    private static final LocalDate MADE = LocalDate.of(2026, 9, 1), EXPIRES = LocalDate.of(2028, 9, 1);

    private GoodsReceiptNote grn;

    /** GRN 50 of PO 30: AWAITING_INSPECTION, arrived 08:00; line 1 part 10 document 10, line 2 part 11 document 5. */
    @BeforeEach
    void data() {
        grn = withId(GoodsReceiptNote.supplierDelivery(30, "PX-001", NOW.toLocalDate(), null, null,
                NOW.withHour(8)), 50);
        grnLines.add(withId(GoodsReceiptNoteLine.expected(50, PAD, 60000L, 10), 1));
        grnLines.add(withId(GoodsReceiptNoteLine.expected(50, OIL, 70000L, 5), 2));
        when(receipts.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());
        when(receipts.findByIdForUpdate(50L)).thenAnswer(inv -> Optional.of(grn));
    }

    private static InspectionResultRequest.Line line(long lineId, Integer documentQty, int delivered, Integer wrong,
                                                     Integer damaged) {
        return new InspectionResultRequest.Line(lineId, documentQty, delivered, wrong, damaged, "L-2609", MADE,
                EXPIRES);
    }

    private static InspectionResultRequest request(LocalDateTime arrivedAt, String note,
                                                   InspectionResultRequest.Line... lines) {
        return new InspectionResultRequest(arrivedAt, List.of(lines), List.of(), note);
    }

    private GoodsReceiptDetail record(InspectionResultRequest request) {
        GoodsReceiptDetail result = service.recordResult(staff, 50, request);
        assertThat(result.id()).isEqualTo(50);
        assertThat(result.status()).isEqualTo(GoodsReceiptNoteStatus.AWAITING_CONFIRMATION);
        return result;
    }

    private void assertRefused(InspectionResultRequest request, HttpStatus status, String code) {
        assertBusiness(() -> service.recordResult(staff, 50, request), status, code);
        verify(warehouseTasks, never()).submitResult(any(), any(), anyLong());
        assertThat(grn.getStatus()).isNotEqualTo(GoodsReceiptNoteStatus.AWAITING_CONFIRMATION);
    }

    @Test
    @DisplayName("UTCID01 N: both parts passed in full; waits for the Branch Manager")
    void utc01() {
        GoodsReceiptDetail result = record(request(null, null, line(1, null, 10, 0, 0), line(2, null, 5, null, null)));

        assertThat(result.recordedById()).isEqualTo(7L);
        assertThat(result.lines().get(0).passedQty()).isEqualTo(10);
        assertThat(result.lines().get(0).manufacturerLotNo()).isEqualTo("L-2609");
        assertThat(result.lines().get(1).passedQty()).isEqualTo(5);
        verify(warehouseTasks).submitResult(staff, TaskType.INSPECTION, 50);
        verify(notifications).notifyStoreManagers(eq(2L), eq("GOODS_RECEIPT_SUBMITTED"), anyString(), anyString(),
                eq(50L));
    }

    @Test
    @DisplayName("UTCID02 N: part 10 has 1 wrong item and 1 damaged -> passed 8 (BR-24), note kept")
    void utc02() {
        GoodsReceiptDetail result = record(request(null, "1 má phanh sai mã, 1 bị móp", line(1, null, 10, 1, 1),
                line(2, null, 5, 0, 0)));

        assertThat(result.inspectionNote()).isEqualTo("1 má phanh sai mã, 1 bị móp");
        GoodsReceiptDetail.Line pad = result.lines().get(0);
        assertThat(pad.wrongItemQty()).isEqualTo(1);
        assertThat(pad.damagedQty()).isEqualTo(1);
        assertThat(pad.passedQty()).isEqualTo(8);
    }

    @Test
    @DisplayName("UTCID03 N: part 11 not delivered (short 5); arrival corrected to 07:00")
    void utc03() {
        GoodsReceiptDetail result = record(request(NOW.withHour(7), "Thiếu nhớt", line(1, null, 10, 0, 0)));

        assertThat(result.arrivedAt()).isEqualTo(NOW.withHour(7));
        assertThat(result.lines().get(1).deliveredQty()).isZero();
        assertThat(result.lines().get(1).shortQty()).isEqualTo(5);
    }

    @Test
    @DisplayName("UTCID04 B: all 5 oil wrong or damaged -> passed 0")
    void utc04() {
        GoodsReceiptDetail result = record(request(null, "Toàn bộ dầu hỏng", line(1, null, 10, 0, 0),
                line(2, null, 5, 2, 3)));

        GoodsReceiptDetail.Line oil = result.lines().get(1);
        assertThat(oil.wrongItemQty()).isEqualTo(2);
        assertThat(oil.damagedQty()).isEqualTo(3);
        assertThat(oil.passedQty()).isZero();
    }

    @Test
    @DisplayName("UTCID05 B: wrong 3 + damaged 3 > delivered 5 on line 2 -> MSG27")
    void utc05() {
        assertRefused(request(null, "x", line(1, null, 10, 0, 0), line(2, null, 5, 3, 3)), HttpStatus.BAD_REQUEST,
                "MSG27");
    }

    @Test
    @DisplayName("UTCID06 A: line 99 is not on GRN 50 -> MSG136")
    void utc06() {
        assertRefused(request(null, null, line(99, null, 1, 0, 0)), HttpStatus.BAD_REQUEST, "MSG136");
    }

    @Test
    @DisplayName("UTCID07 A: line 1 given twice -> MSG136")
    void utc07() {
        assertRefused(request(null, null, line(1, null, 1, 0, 0), line(1, null, 2, 0, 0)), HttpStatus.BAD_REQUEST,
                "MSG136");
    }

    @Test
    @DisplayName("UTCID08 A: expiry before manufacture -> MSG137")
    void utc08() {
        assertRefused(request(null, null, new InspectionResultRequest.Line(1L, null, 10, 0, 0, null, MADE,
                LocalDate.of(2026, 8, 31))), HttpStatus.BAD_REQUEST, "MSG137");
    }

    @Test
    @DisplayName("UTCID09 A: part 10 short 2 and the note is blank -> MSG265")
    void utc09() {
        assertRefused(request(null, "  ", line(1, null, 8, 0, 0), line(2, null, 5, 0, 0)), HttpStatus.BAD_REQUEST,
                "MSG265");
    }

    @Test
    @DisplayName("UTCID10 B: both lines have document quantity 0 and nothing came -> MSG140")
    void utc10() {
        grnLines.replaceAll(l -> withId(GoodsReceiptNoteLine.expected(50, l.getProductId(), l.getUnitPrice(), 0),
                l.getId()));

        assertRefused(request(null, null, line(1, null, 0, 0, 0)), HttpStatus.BAD_REQUEST, "MSG140");
    }

    @Test
    @DisplayName("UTCID11 A: arrival 09:05, after now -> MSG138")
    void utc11() {
        assertRefused(request(NOW.plusMinutes(5), null, line(1, null, 10, 0, 0), line(2, null, 5, 0, 0)),
                HttpStatus.BAD_REQUEST, "MSG138");
    }

    @Test
    @DisplayName("UTCID12 A: GRN 50 already AWAITING_CONFIRMATION -> MSG29")
    void utc12() {
        with(grn, "status", GoodsReceiptNoteStatus.AWAITING_CONFIRMATION);

        assertBusiness(() -> service.recordResult(staff, 50, request(null, null, line(1, null, 10, 0, 0))),
                HttpStatus.CONFLICT, "MSG29");
        verify(warehouseTasks, never()).submitResult(any(), any(), anyLong());
    }

    @Test
    @DisplayName("UTCID13 A: user 7 does not hold the inspection task -> MSG05")
    void utc13() {
        doThrow(new BusinessException(HttpStatus.FORBIDDEN, "MSG05")).when(warehouseTasks)
                .requireAssignee(staff, TaskType.INSPECTION, 50);

        assertRefused(request(null, null, line(1, null, 10, 0, 0)), HttpStatus.FORBIDDEN, "MSG05");
    }

    @Test
    @DisplayName("UTCID14 A: GRN 50 does not exist -> MSG132")
    void utc14() {
        when(receipts.findByIdForUpdate(50L)).thenReturn(Optional.empty());

        assertRefused(request(null, null, line(1, null, 10, 0, 0)), HttpStatus.NOT_FOUND, "MSG132");
    }

    @Test
    @DisplayName("UTCID15 N: the inspector corrects the document quantity of part 10 to 12")
    void utc15() {
        GoodsReceiptDetail result = record(request(null, "Chứng từ ghi 12, giao 10", line(1, 12, 10, 0, 0),
                line(2, null, 5, 0, 0)));

        GoodsReceiptDetail.Line pad = result.lines().get(0);
        assertThat(pad.documentQty()).isEqualTo(12);
        assertThat(pad.deliveredQty()).isEqualTo(10);
        assertThat(pad.shortQty()).isEqualTo(2);
        assertThat(result.lines().get(1).documentQty()).isEqualTo(5);
    }

    @Test
    @DisplayName("UTCID16 B: no lot, manufacture or expiry date for part 10, no expiry for part 11")
    void utc16() {
        GoodsReceiptDetail result = record(request(null, null,
                new InspectionResultRequest.Line(1L, null, 10, 0, 0, null, null, null),
                new InspectionResultRequest.Line(2L, null, 5, 0, 0, "L-2609", MADE, null)));

        GoodsReceiptDetail.Line pad = result.lines().get(0);
        assertThat(pad.manufacturerLotNo()).isNull();
        assertThat(pad.manufactureDate()).isNull();
        assertThat(pad.expiryDate()).isNull();
        assertThat(result.lines().get(1).expiryDate()).isNull();
    }
}
