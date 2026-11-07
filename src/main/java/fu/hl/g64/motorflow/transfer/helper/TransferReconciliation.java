package fu.hl.g64.motorflow.transfer.helper;

import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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

    /**
     * @param received      passed plus damaged on confirmed receipts at the destination, at most the dispatched quantity
     * @param extraReceived what those receipts brought above the dispatched quantity (BR-68)
     * @param returned      passed plus damaged on confirmed receipts back at the source (BR-61)
     * @param loss          approved loss whose adjustment was applied (BR-66)
     * @param unresolved    dispatched minus every final result (BR-60)
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
            int arrived = receipt == null ? 0 : (int) receipt.getReceived();
            int returned = receipt == null ? 0 : (int) receipt.getReturned();
            int received = Math.min(arrived, dispatched);
            int loss = losses.getOrDefault(line.getProductId(), 0L).intValue();
            return new Part(dispatched, received, arrived - received, returned, loss,
                    Math.max(dispatched - received - returned - loss, 0));
        }));
    }
}
