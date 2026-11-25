package fu.hl.g64.motorflow.transfer.helper;

import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderLineRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * BR-60: what each dispatched part of a transfer order has become. Every view, check and closing of a transfer reads
 * its figures from here, so they never disagree.
 */
@Component
@RequiredArgsConstructor
public class TransferReconciliation {

    private final TransferOrderLineRepository lines;
    private final TransferOrderRepository orders;

    /**
     * @param received      passed plus damaged on confirmed receipts at the destination, less their excess: exactly
     *                      what left IN_TRANSIT there
     * @param extraReceived the excess of those receipts, goods above what they expected, never in transit (BR-68)
     * @param returned      passed plus damaged on confirmed receipts back at the source (BR-61, orders before PM 23/11)
     * @param loss          approved loss whose adjustment was applied (BR-66, orders before PM 23/11)
     * @param unresolved    what is still in transit at the destination for the order
     * @param closedShortage written off as lost on closing (PM 23/11: the short part nobody decided on)
     * @param rules         PM 23/11 figures from the order's discrepancy events
     *                      <p>Invariant: destination IN_TRANSIT of the order = unresolved.
     */
    public record Part(int dispatched, int received, int extraReceived, int returned, int loss, int unresolved,
                       int damaged, int legacyExcess, int pendingExcess, int closedShortage, Rules rules) {
        public Part(int dispatched, int received, int extraReceived, int returned, int loss, int unresolved) {
            this(dispatched, received, extraReceived, returned, loss, unresolved, 0, extraReceived, 0, 0, Rules.NONE);
        }

        public int passed() {
            return received - damaged;
        }

        /**
         * Thiếu: what never arrived and is not settled yet: dispatched less what arrived, came back, was lost or is
         * not to be replaced. A wrong item never counts: the part ordered did not arrive.
         */
        public int shortage() {
            return Math.max(unresolved - toSource(), 0);
        }

        /** Không giao bù not moved yet: on closing it leaves transit back to the source's available stock. */
        public int toSource() {
            return rules.noReplacement() - rules.noReplacementDone();
        }

        /** Chờ quyết: shortage the destination's BM has chosen nothing for (late delivery or no replacement). */
        public int awaitingDecision() {
            return Math.max(shortage() - rules.latePending() - rules.legacyClaims(), 0);
        }

        /**
         * Written off as lost on closing: the shortage nobody chose anything for. PM Q1 23/11: a late delivery is not
         * lost; the order cannot close until it arrives (MSG348).
         */
        public int toLoss() {
            return awaitingDecision();
        }

        /** Mất: written off, by the closing (PM 23/11) or by an applied loss adjustment (before). */
        public int lost() {
            return loss + closedShortage;
        }

        /**
         * Giao sai chờ: wrong items neither on a phiếu nhận hàng giao sai awaiting or confirmed by the source nor
         * handed back to it (GVHD 24/11). A rejected phiếu leaves its items here again.
         */
        public int wrongPending() {
            return Math.max(rules.wrongItem() - rules.wrongAwaitingSource() - rules.wrongConfirmed()
                    - rules.wrongReturned(), 0);
        }
    }

    /**
     * PM 23/11. {@code wrongItem} recorded on confirmed receipts at the destination; {@code wrongAwaitingSource} on
     * phiếu nhận hàng giao sai or hand-backs awaiting the source's BM (review 25/11), {@code wrongConfirmed} on phiếu
     * it confirmed, {@code wrongReturned} handed back and confirmed by it (GVHD 24/11);
     * {@code noReplacement} decided not to be replaced,
     * {@code noReplacementDone} of which moved back on closing; {@code latePending} decided to come later and not
     * arrived; {@code legacyClaims} losses awaiting their adjustment and return decisions of orders before 23/11;
     * {@code miscounted} the Owner found the receiver counted the line wrong.
     */
    public record Rules(int wrongItem, int wrongAwaitingSource, int wrongConfirmed, int wrongReturned,
                        int noReplacement, int noReplacementDone, int latePending, int legacyClaims,
                        boolean miscounted) {
        public static final Rules NONE = new Rules(0, 0, 0, 0, 0, 0, 0, 0, false);
    }

    public Map<Long, Part> byProduct(long transferOrderId, List<TransferOrderLine> orderLines) {
        Map<Long, TransferOrderLineRepository.ReceiptTotals> receipts = lines.confirmedReceipts(transferOrderId).stream()
                .collect(Collectors.toMap(TransferOrderLineRepository.ReceiptTotals::getProductId, Function.identity()));
        Map<Long, Long> losses = lines.appliedLoss(transferOrderId).stream()
                .collect(Collectors.toMap(TransferOrderLineRepository.ProductQty::getProductId,
                        TransferOrderLineRepository.ProductQty::getQty));
        Map<Long, Long> shortages = lines.acknowledgedShortages(transferOrderId).stream()
                .collect(Collectors.toMap(TransferOrderLineRepository.ProductQty::getProductId,
                        TransferOrderLineRepository.ProductQty::getQty));
        Map<Long, TransferOrderLineRepository.EventTotals> events = lines.eventTotals(transferOrderId).stream()
                .collect(Collectors.toMap(TransferOrderLineRepository.EventTotals::getProductId, Function.identity()));
        Map<Long, Long> claims = pendingClaims(transferOrderId);
        return orderLines.stream().collect(Collectors.toMap(TransferOrderLine::getProductId, line -> {
            int dispatched = line.getDispatchedQty() == null ? 0 : line.getDispatchedQty();
            TransferOrderLineRepository.ReceiptTotals receipt = receipts.get(line.getProductId());
            TransferOrderLineRepository.EventTotals event = events.get(line.getProductId());
            if (event != null && event.getMiscounts() > 0) {
                // PM 23/11 case 7: the destination holds what was dispatched; excess, damage, wrong items and
                // shortage of the line are void. The receipts keep what the staff recorded.
                return new Part(dispatched, dispatched, 0, 0, 0, 0, 0, 0, 0, 0,
                        new Rules(0, 0, 0, 0, 0, 0, 0, 0, true));
            }
            int extra = receipt == null ? 0 : (int) receipt.getExcess();
            int received = receipt == null ? 0 : (int) receipt.getReceived() - extra;
            int returned = receipt == null ? 0 : (int) receipt.getReturned();
            int loss = losses.getOrDefault(line.getProductId(), 0L).intValue();
            int closedShortage = shortages.getOrDefault(line.getProductId(), 0L).intValue();
            Rules rules = new Rules(receipt == null ? 0 : Math.toIntExact(receipt.getWrongItem()),
                    event == null ? 0 : Math.toIntExact(event.getWrongAwaitingSource()),
                    event == null ? 0 : Math.toIntExact(event.getWrongConfirmed()),
                    event == null ? 0 : Math.toIntExact(event.getWrongReturned()),
                    event == null ? 0 : Math.toIntExact(event.getNoReplacement()),
                    event == null ? 0 : Math.toIntExact(event.getNoReplacementDone()),
                    event == null ? 0 : Math.toIntExact(event.getLatePending()),
                    claims.getOrDefault(line.getProductId(), 0L).intValue(), false);
            return new Part(dispatched, received, extra, returned, loss,
                    Math.max(dispatched - received - returned - loss - closedShortage - rules.noReplacementDone(), 0),
                    receipt == null ? 0 : Math.toIntExact(receipt.getDamaged()),
                    receipt == null ? 0 : Math.toIntExact(receipt.getLegacyExcess()),
                    receipt == null ? 0 : Math.toIntExact(receipt.getPendingExcess()), closedShortage, rules);
        }));
    }

    /** UC-70, UC-77: what the request's transfer orders delivered per part, each order at most what it dispatched. */
    public Map<Long, Long> fulfilled(long replenishmentRequestId) {
        Map<Long, Long> total = new HashMap<>();
        for (TransferOrderRepository.LinkedOrder row : orders.findLinkedOrders(replenishmentRequestId)) {
            long id = row.getTransferOrder().getId();
            byProduct(id, lines.findByTransferOrderIdOrderByProductId(id))
                    .forEach((productId, part) -> total.merge(productId, (long) part.passed(), Long::sum));
        }
        return total;
    }

    /**
     * Unresolved per part less what open receipts already expect, what losses awaiting their adjustment already
     * claim and what coordinators decided to send back but the source has not opened a return for yet: the most a new
     * receipt, return decision or loss may still cover, so two of them never settle the same unit. An open return is
     * already counted with the open receipts, so only the part of a return decision above it is subtracted here.
     */
    /**
     * UC-106 at the destination: whether goods of the order can arrive now. {@code blockedCode} is MSG29 unless the
     * order is in transit or reconciling, MSG211 while a receipt at the destination is not confirmed, MSG210 when
     * nothing is left uncovered, else null; {@code uncovered} is what each part may still receive. The arrival and the
     * receiving task (TRANSFER_RECEIVING) read the same rule from here.
     */
    public record Arrival(String blockedCode, Map<Long, Integer> uncovered) {
    }

    public Arrival arrival(TransferOrder order) {
        if (order.getStatus() != TransferOrderStatus.IN_TRANSIT && order.getStatus() != TransferOrderStatus.RECONCILING) {
            return new Arrival("MSG29", Map.of());
        }
        boolean open = orders.findReceipts(order.getId()).stream().anyMatch(receipt ->
                receipt.getReceiptType() == ReceiptType.TRANSFER_IN
                        && receipt.getStatus() != GoodsReceiptNoteStatus.CONFIRMED);
        if (open) {
            return new Arrival("MSG211", Map.of());
        }
        Map<Long, Integer> uncovered = uncovered(order.getId(), lines.findByTransferOrderIdOrderByProductId(order.getId()));
        if (uncovered.values().stream().allMatch(quantity -> quantity == 0)) {
            return new Arrival("MSG210", uncovered);
        }
        return new Arrival(null, uncovered);
    }

    public Map<Long, Integer> uncovered(long transferOrderId, List<TransferOrderLine> orderLines) {
        Map<Long, Long> claimed = pendingClaims(transferOrderId);
        lines.openReceiptQty(transferOrderId).forEach(row -> claimed.merge(row.getProductId(), row.getQty(), Long::sum));
        // PM 23/11: what is not to be replaced never comes, so a receipt never expects it.
        return byProduct(transferOrderId, orderLines).entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> Math.max(entry.getValue().shortage()
                        - claimed.getOrDefault(entry.getKey(), 0L).intValue(), 0)));
    }

    /**
     * Per part, what losses awaiting their adjustment already claim plus the part of the coordinators' return
     * decisions above the returns the source has already opened (an open return is counted with its own quantity).
     */
    public Map<Long, Long> pendingClaims(long transferOrderId) {
        Map<Long, Long> claimed = new HashMap<>();
        lines.pendingLoss(transferOrderId).forEach(row -> claimed.merge(row.getProductId(), row.getQty(), Long::sum));
        Map<Long, Long> opened = new HashMap<>();
        lines.openReturnQty(transferOrderId).forEach(row -> opened.merge(row.getProductId(), row.getQty(), Long::sum));
        lines.pendingReturnDecisions(transferOrderId).forEach(row -> claimed.merge(row.getProductId(),
                Math.max(row.getQty() - opened.getOrDefault(row.getProductId(), 0L), 0), Long::sum));
        return claimed;
    }
}
