package fu.hl.g64.motorflow.receiving.helper;

import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement.Progress;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement.Standing;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * UC-106: whether a purchase order can still get a goods receipt note: a delivery of its outstanding lines, or a
 * replacement delivery for its discrepancy cases. The PO detail flags and the receiving task (SUPPLIER_RECEIVING)
 * read the same rule from here.
 */
@Component
@RequiredArgsConstructor
public class PurchaseOrderReceiving {

    private final ReceivingLedger ledger;
    private final GoodsReceiptNoteRepository receipts;
    private final DiscrepancyCaseRepository discrepancies;
    private final DiscrepancySettlement settlement;

    /** A discrepancy case of the PO and what a replacement delivery may still bring for it. */
    public record OpenCase(DiscrepancyCase found, int openReplacementQty) {
    }

    /** {@code receipt}: a delivery can be recorded; {@code replacement}: a replacement delivery can be recorded. */
    public record Openness(boolean receipt, boolean replacement, ReceivingLedger.Ledger standing,
                           List<OpenCase> cases) {

        public boolean any() {
            return receipt || replacement;
        }
    }

    public Openness of(PurchaseOrder order) {
        ReceivingLedger.Ledger standing = ledger.of(order.getId());
        List<Long> deliveryIds = receipts.findByPurchaseOrderIdOrderByArrivedAtAscIdAsc(order.getId()).stream()
                .map(GoodsReceiptNote::getId).toList();
        List<DiscrepancyCase> found = deliveryIds.isEmpty() ? List.of()
                : discrepancies.findByGoodsReceiptNoteIdInOrderByIdAsc(deliveryIds);
        Map<Long, Progress> progress = settlement.progress(found.stream().map(DiscrepancyCase::getId).toList());
        List<OpenCase> cases = found.stream()
                .map(c -> new OpenCase(c, openReplacement(c, progress.get(c.getId())))).toList();
        return new Openness(receiptOpen(order, standing), cases.stream().anyMatch(c -> c.openReplacementQty() > 0),
                standing, cases);
    }

    /** A delivery of outstanding lines: the PO waits for goods and some line is not delivered in full (BR-22). */
    public static boolean receiptOpen(PurchaseOrder order, ReceivingLedger.Ledger standing) {
        return (order.getStatus() == PurchaseOrderStatus.AWAITING_DELIVERY
                || order.getStatus() == PurchaseOrderStatus.RECEIVING) && standing.anyOutstanding();
    }

    /** What a replacement delivery may still bring for the case: nothing once it is closed. */
    public static int openReplacement(DiscrepancyCase found, Progress progress) {
        return found.isOpen() ? new Standing(found, progress).openReplacement() : 0;
    }
}
