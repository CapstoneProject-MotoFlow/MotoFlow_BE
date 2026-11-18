package fu.hl.g64.motorflow.receiving.helper;

import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import org.springframework.http.HttpStatus;

import java.util.List;

/** G06: an inspection note is required when the recorded result reports a quantity discrepancy. */
public final class InspectionNoteRules {

    private InspectionNoteRules() {
    }

    public static boolean lineHasDiscrepancy(GoodsReceiptNoteLine line) {
        return line.shortQty() > 0 || line.getWrongItemQty() > 0 || line.getDamagedQty() > 0 || line.getExcessQty() > 0
                || line.getDeliveredQty() > line.getDocumentQty();
    }

    public static void requireWhenNeeded(List<GoodsReceiptNoteLine> lines, String inspectionNote) {
        if (lines.stream().anyMatch(InspectionNoteRules::lineHasDiscrepancy)
                && (inspectionNote == null || inspectionNote.isBlank())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG265");
        }
    }
}