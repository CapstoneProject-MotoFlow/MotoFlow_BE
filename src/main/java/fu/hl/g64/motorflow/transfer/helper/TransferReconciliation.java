package fu.hl.g64.motorflow.transfer.helper;

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
     * @param returned      passed plus damaged on confirmed receipts back at the source (BR-61)
     * @param loss          approved loss whose adjustment was applied (BR-66)
     * @param unresolved    dispatched minus every final result (BR-60)
     *                      <p>Invariant: destination IN_TRANSIT of the order = dispatched - received - returned - loss.
     */
    public record Part(int dispatched, int received, int extraReceived, int returned, int loss, int unresolved) {
    }

    public Map<Long, Part> byProduct(long transferOrderId, List<TransferOrderLine> orderLines) {
        Map<Long, TransferOrderLineRepository.ReceiptTotals> receipts = lines.confirmedReceipts(transferOrderId).stream()
                .collect(Collectors.toMap(TransferOrderLineRepository.ReceiptTotals::getProductId, Function.identity()));
        Map<Long, Long> losses = lines.appliedLoss(transferOrderId).stream()
                .collect(Collectors.toMap(TransferOrderLineRepository.ProductQty::getProductId,
                        TransferOrderLineRepository.ProductQty::getQty));
        return orderLines.stream().collect(Collectors.toMap(TransferOrderLine::getProductId, line -> {
            int dispatched = line.getDispatchedQty() == null ? 0 : line.getDispatchedQty();
            TransferOrderLineRepository.ReceiptTotals receipt = receipts.get(line.getProductId());
            int extra = receipt == null ? 0 : (int) receipt.getExcess();
            int received = receipt == null ? 0 : (int) receipt.getReceived() - extra;
            int returned = receipt == null ? 0 : (int) receipt.getReturned();
            int loss = losses.getOrDefault(line.getProductId(), 0L).intValue();
            return new Part(dispatched, received, extra, returned, loss,
                    Math.max(dispatched - received - returned - loss, 0));
        }));
    }

    /** UC-70, UC-77: what the request's transfer orders delivered per part, each order at most what it dispatched. */
    public Map<Long, Long> fulfilled(long replenishmentRequestId) {
        Map<Long, Long> total = new HashMap<>();
        for (TransferOrderRepository.LinkedOrder row : orders.findLinkedOrders(replenishmentRequestId)) {
            long id = row.getTransferOrder().getId();
            byProduct(id, lines.findByTransferOrderIdOrderByProductId(id))
                    .forEach((productId, part) -> total.merge(productId, (long) part.received(), Long::sum));
        }
        return total;
    }

    /**
     * Unresolved per part less what open receipts already expect, what losses awaiting their adjustment already
     * claim and what coordinators decided to send back but the source has not opened a return for yet: the most a new
     * receipt, return decision or loss may still cover, so two of them never settle the same unit. An open return is
     * already counted with the open receipts, so only the part of a return decision above it is subtracted here.
     */
    public Map<Long, Integer> uncovered(long transferOrderId, List<TransferOrderLine> orderLines) {
        Map<Long, Long> claimed = new HashMap<>();
        lines.openReceiptQty(transferOrderId).forEach(row -> claimed.merge(row.getProductId(), row.getQty(), Long::sum));
        lines.pendingLoss(transferOrderId).forEach(row -> claimed.merge(row.getProductId(), row.getQty(), Long::sum));
        Map<Long, Long> opened = new HashMap<>();
        lines.openReturnQty(transferOrderId).forEach(row -> opened.merge(row.getProductId(), row.getQty(), Long::sum));
        lines.pendingReturnDecisions(transferOrderId).forEach(row -> claimed.merge(row.getProductId(),
                Math.max(row.getQty() - opened.getOrDefault(row.getProductId(), 0L), 0), Long::sum));
        return byProduct(transferOrderId, orderLines).entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> Math.max(entry.getValue().unresolved()
                        - claimed.getOrDefault(entry.getKey(), 0L).intValue(), 0)));
    }
}
