package fu.hl.g64.motorflow.receiving.helper;

import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrderLine;
import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the deliveries of a PO settled so far, part by part (UC-41, UC-106, UC-44).
 *
 * <p>A confirmed delivery covers the quantity of its delivery document, or the delivered quantity when more came.
 * Whatever of it was short, wrong or damaged is followed by a discrepancy case (UC-46), not by the PO: so a line is
 * outstanding by ordered - covered (never below zero), and the PO is fully received when no line is outstanding
 * (BR-22), even while a case still waits for returns or replacements. A replacement delivery (UC-106 Step
 * 1_Supplier replacement) settles cases and covers nothing of the PO.
 *
 * <p>{@code receivedQty} is what confirmed deliveries, replacements included, put into available stock for the part
 * (passed minus excess); excess that Owner accepted afterwards is {@code acceptedExcessQty}.
 */
@Component
@RequiredArgsConstructor
public class ReceivingLedger {

    private final PurchaseOrderLineRepository orderLines;
    private final GoodsReceiptNoteRepository receipts;
    private final GoodsReceiptNoteLineRepository receiptLines;
    private final DiscrepancyResolutionEventRepository events;

    public record Part(int orderedQty, int coveredQty, int receivedQty, int acceptedExcessQty) {
        public int outstandingQty() {
            return Math.max(orderedQty - coveredQty, 0);
        }
    }

    /** {@code parts}: one entry per PO line, in line order. */
    public record Ledger(Map<Long, Part> parts, Set<Long> replacementReceiptIds) {

        /** What the PO still expects of the part; 0 for a part that is not on the PO. */
        public int outstanding(long productId) {
            Part part = parts.get(productId);
            return part == null ? 0 : part.outstandingQty();
        }

        public boolean anyOutstanding() {
            return parts.values().stream().anyMatch(part -> part.outstandingQty() > 0);
        }

        public boolean isReplacement(long receiptId) {
            return replacementReceiptIds.contains(receiptId);
        }
    }

    /** Reads the PO's lines and deliveries. A caller that decides on it holds the PO lock. */
    public Ledger of(long purchaseOrderId) {
        List<PurchaseOrderLine> lines = orderLines.findByPurchaseOrderIdOrderByIdAsc(purchaseOrderId);
        List<GoodsReceiptNote> deliveries = receipts.findByPurchaseOrderIdOrderByArrivedAtAscIdAsc(purchaseOrderId);
        List<Long> ids = deliveries.stream().map(GoodsReceiptNote::getId).toList();
        List<GoodsReceiptNoteLine> results = ids.isEmpty() ? List.of()
                : receiptLines.findByGoodsReceiptNoteIdInOrderByIdAsc(ids);
        return compute(lines, deliveries, results, replacementReceiptIds(ids));
    }

    /** The given receipts that are replacement deliveries. */
    public Set<Long> replacementReceiptIds(Collection<Long> receiptIds) {
        return receiptIds.isEmpty() ? Set.of() : new HashSet<>(events.findReplacementReceiptIds(receiptIds));
    }

    public static Ledger compute(List<PurchaseOrderLine> lines, List<GoodsReceiptNote> deliveries,
                                 List<GoodsReceiptNoteLine> results, Set<Long> replacementReceiptIds) {
        Set<Long> confirmed = deliveries.stream().filter(r -> r.getStatus() == GoodsReceiptNoteStatus.CONFIRMED)
                .map(GoodsReceiptNote::getId).collect(Collectors.toSet());
        Map<Long, Integer> covered = new HashMap<>();
        Map<Long, Integer> received = new HashMap<>();
        Map<Long, Integer> acceptedExcess = new HashMap<>();
        for (GoodsReceiptNoteLine result : results) {
            if (!confirmed.contains(result.getGoodsReceiptNoteId())) {
                continue;
            }
            received.merge(result.getProductId(), result.acceptedQty(), Integer::sum);
            if (result.getExcessDecision() == ExcessDecision.ACCEPTED) {
                acceptedExcess.merge(result.getProductId(), result.getExcessQty(), Integer::sum);
            }
            if (!replacementReceiptIds.contains(result.getGoodsReceiptNoteId())) {
                covered.merge(result.getProductId(), Math.max(result.getDocumentQty(), result.getDeliveredQty()),
                        Integer::sum);
            }
        }
        Map<Long, Part> parts = new LinkedHashMap<>();
        for (PurchaseOrderLine line : lines) {
            long productId = line.getProductId();
            parts.put(productId, new Part(line.getOrderedQty(),
                    Math.min(line.getOrderedQty(), covered.getOrDefault(productId, 0)),
                    received.getOrDefault(productId, 0), acceptedExcess.getOrDefault(productId, 0)));
        }
        return new Ledger(parts, Set.copyOf(replacementReceiptIds));
    }
}
