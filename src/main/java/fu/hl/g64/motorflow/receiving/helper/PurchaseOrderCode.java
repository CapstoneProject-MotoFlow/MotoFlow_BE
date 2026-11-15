package fu.hl.g64.motorflow.receiving.helper;

/**
 * How a purchase order is named to people (PM 15/11, FE review PR #43 G01 and PR #44 R-05): "đơn nhập hàng" with the
 * code "DNH-" and the id padded to four digits, as the FE shows it ({@code documentCode}).
 */
public final class PurchaseOrderCode {

    private PurchaseOrderCode() {
    }

    /** For example DNH-0012. */
    public static String of(long id) {
        return "DNH-%04d".formatted(id);
    }
}
