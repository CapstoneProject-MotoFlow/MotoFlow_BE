package fu.hl.g64.motorflow.report.helper;

import fu.hl.g64.motorflow.report.dto.SalesCollectionReport;
import fu.hl.g64.motorflow.report.dto.SalesCollectionReport.Figures;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static fu.hl.g64.motorflow.report.repository.ReportQueries.number;

/**
 * UC-89: the accountant's workbook. Five sheets: revenue and collection by day, sales lines, purchases (supplier
 * deliveries), payments, and adjustment requests applied in the period. Amounts are whole VND.
 */
@Component
public class AccountingWorkbook {

    private static final int WINDOW = 200;

    public byte[] write(ReportScope scope, SalesCollectionReport daily, List<Map<String, Object>> sales,
                        List<Map<String, Object>> purchases, List<Map<String, Object>> payments,
                        List<Map<String, Object>> adjustments, Map<Long, String> storeNames) {
        try (SXSSFWorkbook book = new SXSSFWorkbook(WINDOW); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(book);
            String store = scope.storeId() == null ? "Tất cả cửa hàng" : storeNames.get(scope.storeId());
            String title = "Từ %s đến %s - %s".formatted(scope.first(), scope.last(), store);

            Sheet revenue = sheet(book, styles, "Doanh thu", title, "Ngày", "Đơn hoàn tất", "Doanh thu",
                    "Thu tiền đơn", "Hoàn tiền", "Thu nợ", "Tổng thu", "Bán chịu", "Điều chỉnh doanh thu",
                    "Điều chỉnh thu tiền", "Điều chỉnh công nợ", "Thu cọc", "Trả lại cọc", "Cọc dùng cho đơn",
                    "Cọc khách mất", "Thu tiền mặt", "Thu chuyển khoản");
            int r = 3;
            for (SalesCollectionReport.PeriodRow p : daily.periods()) {
                figures(revenue.createRow(r++), styles, p.from(), p.figures());
            }
            Row total = revenue.createRow(r++);
            text(total, 0, "Tổng", styles.bold);
            figuresFrom(total, 1, styles, daily.total());
            Row owed = revenue.createRow(r);
            text(owed, 0, "Công nợ còn lại cuối kỳ", styles.bold);
            money(owed, 2, daily.total().receivableAtEnd(), styles);

            Sheet sold = sheet(book, styles, "Bán hàng", title, "Đơn", "Ngày hoàn tất", "Cửa hàng", "Khách hàng",
                    "Hình thức", "SKU", "Tên phụ tùng", "ĐVT", "Số lượng", "Đơn giá", "Thành tiền", "Giá vốn");
            r = 3;
            for (Map<String, Object> s : sales) {
                Row row = sold.createRow(r++);
                text(row, 0, "#" + s.get("order_id"), null);
                date(row, 1, s.get("completed_at"), styles);
                text(row, 2, s.get("store_name"), null);
                text(row, 3, s.get("customer"), null);
                text(row, 4, s.get("completion_type"), null);
                text(row, 5, s.get("sku"), null);
                text(row, 6, s.get("part_name"), null);
                text(row, 7, s.get("base_unit"), null);
                money(row, 8, number(s.get("quantity")), styles);
                money(row, 9, number(s.get("actual_unit_price")), styles);
                money(row, 10, number(s.get("amount")), styles);
                money(row, 11, number(s.get("cost")), styles);
            }

            Sheet bought = sheet(book, styles, "Mua hàng", title, "Phiếu nhập", "Ngày xác nhận", "Cửa hàng",
                    "Nhà cung cấp", "Đơn mua", "Số chứng từ giao", "SKU", "Tên phụ tùng", "ĐVT", "Số lượng nhập",
                    "Đơn giá", "Thành tiền");
            r = 3;
            for (Map<String, Object> p : purchases) {
                Row row = bought.createRow(r++);
                long quantity = number(p.get("quantity"));
                long price = number(p.get("unit_price"));
                text(row, 0, "#" + p.get("receipt_id"), null);
                date(row, 1, p.get("confirmed_at"), styles);
                text(row, 2, p.get("store_name"), null);
                text(row, 3, p.get("supplier_name"), null);
                text(row, 4, p.get("purchase_order_id") == null ? null : "#" + p.get("purchase_order_id"), null);
                text(row, 5, p.get("delivery_document_no"), null);
                text(row, 6, p.get("sku"), null);
                text(row, 7, p.get("part_name"), null);
                text(row, 8, p.get("base_unit"), null);
                money(row, 9, quantity, styles);
                money(row, 10, price, styles);
                money(row, 11, quantity * price, styles);
            }

            Sheet paid = sheet(book, styles, "Thu tiền", title, "Mã", "Ngày xác nhận", "Loại", "Cửa hàng", "Đơn",
                    "Khách hàng", "Phương thức", "Mã giao dịch", "Số tiền");
            r = 3;
            for (Map<String, Object> p : payments) {
                Row row = paid.createRow(r++);
                text(row, 0, "#" + p.get("id"), null);
                date(row, 1, p.get("verified_at"), styles);
                text(row, 2, p.get("payment_type"), null);
                text(row, 3, storeNames.get(p.get("store_id") == null ? null : number(p.get("store_id"))), null);
                text(row, 4, p.get("sales_order_id") == null ? null : "#" + p.get("sales_order_id"), null);
                text(row, 5, p.get("customer"), null);
                text(row, 6, p.get("payment_method"), null);
                text(row, 7, p.get("transaction_reference"), null);
                long amount = number(p.get("amount"));
                // Money given back (a refund, a deposit given back) leaves the store.
                Object type = p.get("payment_type");
                boolean givenBack = "REFUND".equals(type) || "DEPOSIT_REFUND".equals(type);
                money(row, 8, givenBack ? -amount : amount, styles);
            }

            Sheet adjusted = sheet(book, styles, "Chứng từ điều chỉnh", title, "Đề nghị", "Ngày áp dụng", "Loại",
                    "Cửa hàng", "Chứng từ gốc", "Lý do", "Người đề nghị", "Người duyệt", "Thay đổi tiền",
                    "Thay đổi giá trị kho");
            r = 3;
            for (Map<String, Object> a : adjustments) {
                Row row = adjusted.createRow(r++);
                text(row, 0, "#" + a.get("id"), null);
                date(row, 1, a.get("applied_at"), styles);
                text(row, 2, a.get("adjustment_type"), null);
                text(row, 3, a.get("store_name"), null);
                text(row, 4, a.get("source"), null);
                text(row, 5, a.get("reason"), null);
                text(row, 6, a.get("requested_by"), null);
                text(row, 7, a.get("decided_by"), null);
                money(row, 8, number(a.get("amount_change")), styles);
                money(row, 9, number(a.get("stock_value_change")), styles);
            }

            book.write(out);
            book.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private static Sheet sheet(SXSSFWorkbook book, Styles styles, String name, String title, String... headers) {
        SXSSFSheet sheet = book.createSheet(name);
        text(sheet.createRow(0), 0, name, styles.title);
        text(sheet.createRow(1), 0, title, null);
        Row header = sheet.createRow(2);
        for (int i = 0; i < headers.length; i++) {
            text(header, i, headers[i], styles.bold);
            sheet.setColumnWidth(i, Math.max(12, headers[i].length() + 4) * 256);
        }
        sheet.createFreezePane(0, 3);
        return sheet;
    }

    private static void figures(Row row, Styles styles, LocalDate day, Figures f) {
        Cell cell = row.createCell(0);
        cell.setCellValue(day);
        cell.setCellStyle(styles.date);
        figuresFrom(row, 1, styles, f);
    }

    private static void figuresFrom(Row row, int from, Styles styles, Figures f) {
        long[] values = {f.completedOrders(), f.revenue(), f.orderPayments(), f.refunds(), f.debtCollected(),
                f.totalCollected(), f.creditSales(), f.revenueCorrections(), f.collectionCorrections(),
                f.receivableCorrections(), f.depositsReceived(), f.depositsRefunded(), f.depositsApplied(),
                f.depositsForfeited(), f.cashCollected(), f.transferCollected()};
        for (int i = 0; i < values.length; i++) {
            money(row, from + i, values[i], styles);
        }
    }

    private static void text(Row row, int column, Object value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value != null) {
            cell.setCellValue(value.toString());
        }
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static void money(Row row, int column, Long value, Styles styles) {
        Cell cell = row.createCell(column);
        if (value != null) {
            cell.setCellValue(value);
        }
        cell.setCellStyle(styles.money);
    }

    private static void date(Row row, int column, Object value, Styles styles) {
        Cell cell = row.createCell(column);
        if (value instanceof Timestamp t) {
            cell.setCellValue(t.toLocalDateTime());
            cell.setCellStyle(styles.dateTime);
        }
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle bold;
        final CellStyle money;
        final CellStyle date;
        final CellStyle dateTime;

        Styles(SXSSFWorkbook book) {
            CreationHelper helper = book.getCreationHelper();
            Font boldFont = book.createFont();
            boldFont.setBold(true);
            Font titleFont = book.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            title = book.createCellStyle();
            title.setFont(titleFont);
            bold = book.createCellStyle();
            bold.setFont(boldFont);
            money = book.createCellStyle();
            money.setDataFormat(helper.createDataFormat().getFormat("#,##0"));
            date = book.createCellStyle();
            date.setDataFormat(helper.createDataFormat().getFormat("dd/mm/yyyy"));
            dateTime = book.createCellStyle();
            dateTime.setDataFormat(helper.createDataFormat().getFormat("dd/mm/yyyy hh:mm"));
        }
    }
}
