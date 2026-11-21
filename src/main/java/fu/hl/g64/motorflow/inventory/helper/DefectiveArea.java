package fu.hl.g64.motorflow.inventory.helper;

import java.text.Normalizer;

/**
 * GVHD 20/11: the defective goods area of every warehouse. Damaged, wrong and awaiting-return goods are kept there,
 * and the dented-goods variant of a part is shelved there (stock_balance.storage_area).
 */
public final class DefectiveArea {

    public static final String NAME = "Khu hàng lỗi";

    /** Whether a storage area name means this area, whatever its case. */
    public static boolean is(String area) {
        return area != null && nfc(area).strip().equalsIgnoreCase(NAME);
    }

    /** The name in composed (NFC) form, so a name typed with combining marks equals the stored one. */
    public static String nfc(String text) {
        return text == null ? null : Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    private DefectiveArea() {
    }
}
