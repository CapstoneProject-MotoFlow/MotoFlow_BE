package fu.hl.g64.motorflow.transfer.helper;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.dto.LotQuantity;
import fu.hl.g64.motorflow.inventory.dto.MovePosting;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.sales.helper.PickingGuide;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.helper.SourceLots.Plan;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PM 23/11 case 3: transfer excess (the right part, more than dispatched) awaiting the Owner is accepted, never
 * rejected: the destination keeps it and the source gives it from its sellable stock, the lots of the expiry the
 * destination recorded, else oldest first. The Owner may accept it early on the pending screen; closing the order
 * accepts what is left. Either way it is refused, with nothing posted, when the source cannot cover it (MSG339) or has
 * no sellable lot of that expiry (MSG341).
 */
@Component
@RequiredArgsConstructor
public class TransferExcessAcceptance {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final TransferOrderRepository orders;
    private final GoodsReceiptNoteRepository receipts;
    private final GoodsReceiptNoteLineRepository receiptLines;
    private final ProductRepository products;
    private final StockBalanceRepository balances;
    private final PickingGuide guide;
    private final InventoryPostingService posting;
    private final Clock clock;

    /** The reason accepting would be refused, with its message arguments. */
    public record Refusal(String code, Object... args) {
    }

    /** The excess awaiting the Owner on the order's confirmed receipts at the destination, by part then line. */
    public List<GoodsReceiptNoteLine> pending(long orderId) {
        List<Long> receiptIds = orders.findReceipts(orderId).stream()
                .filter(r -> r.getReceiptType() == ReceiptType.TRANSFER_IN && r.getStatus() == GoodsReceiptNoteStatus.CONFIRMED)
                .map(GoodsReceiptNote::getId).toList();
        if (receiptIds.isEmpty()) {
            return List.of();
        }
        return receiptLines.findByGoodsReceiptNoteIdInAndExcessDecision(receiptIds, ExcessDecision.AWAITING_OWNER)
                .stream().sorted(Comparator.comparing(GoodsReceiptNoteLine::getProductId)
                        .thenComparing(GoodsReceiptNoteLine::getId)).toList();
    }

    /** Whether accepting these lines now would be refused, without locking or posting anything (closing preview). */
    public Refusal check(TransferOrder order, List<GoodsReceiptNoteLine> lines) {
        if (lines.isEmpty()) {
            return null;
        }
        return plans(order, lines).refusal();
    }

    /**
     * Accepts every given line in one go: locks both warehouses' balances of the parts (lower store id first), moves
     * the excess from the source's available stock to the destination's (TRANSFER_EXTRA_DISPATCH, sourced by the
     * receipt) and records the decision on the lines and their receipts.
     */
    public void accept(TransferOrder order, List<GoodsReceiptNoteLine> lines, long performedBy, String reason) {
        if (lines.isEmpty()) {
            return;
        }
        List<Long> productIds = lines.stream().map(GoodsReceiptNoteLine::getProductId).distinct().sorted().toList();
        long source = order.getSourceStoreId();
        long destination = order.getDestStoreId();
        balances.findByStoreForUpdate(Math.min(source, destination), productIds);
        balances.findByStoreForUpdate(Math.max(source, destination), productIds);
        Planned planned = plans(order, lines);
        if (planned.refusal() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, planned.refusal().code(), planned.refusal().args());
        }
        List<MovePosting> moves = new ArrayList<>();
        for (GoodsReceiptNoteLine line : lines) {
            List<LotQuantity> selected = planned.plans().get(line.getId()).lots().stream()
                    .map(lot -> new LotQuantity(lot.lotId(), lot.quantity())).toList();
            moves.add(new MovePosting(line.getProductId(), source, StockStatus.AVAILABLE, destination,
                    StockStatus.AVAILABLE, line.getExcessQty(), selected, MovementType.TRANSFER_EXTRA_DISPATCH,
                    SourceRef.goodsReceiptNote(line.getGoodsReceiptNoteId()), performedBy, reason));
        }
        posting.moveAll(moves);
        lines.forEach(line -> line.decideExcess(ExcessDecision.ACCEPTED));
        LocalDateTime now = LocalDateTime.now(clock);
        new TreeSet<>(lines.stream().map(GoodsReceiptNoteLine::getGoodsReceiptNoteId).toList()).forEach(id ->
                receipts.findByIdForUpdate(id).ifPresent(receipt -> receipt.recordExcessDecision(performedBy, now)));
    }

    private record Planned(Map<Long, Plan> plans, Refusal refusal) {
    }

    /** One plan per line, taken one after another from the same stock so two lines never count the same units. */
    private Planned plans(TransferOrder order, List<GoodsReceiptNoteLine> lines) {
        List<Long> productIds = lines.stream().map(GoodsReceiptNoteLine::getProductId).distinct().toList();
        Map<Long, PickingGuide.Part> available = new HashMap<>(
                guide.parts(order.getSourceStoreId(), productIds, LocalDate.now(clock)));
        Map<Long, Product> parts = products.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Integer> needed = lines.stream().collect(Collectors.groupingBy(GoodsReceiptNoteLine::getProductId,
                LinkedHashMap::new, Collectors.summingInt(GoodsReceiptNoteLine::getExcessQty)));
        Map<Long, Plan> plans = new LinkedHashMap<>();
        for (GoodsReceiptNoteLine line : lines) {
            PickingGuide.Part left = available.get(line.getProductId());
            Plan plan = SourceLots.plan(line.getExcessQty(), line.getExpiryDate(), left);
            String sku = parts.get(line.getProductId()).getSku();
            if ("MSG341".equals(plan.blockedCode())) {
                return new Planned(plans, new Refusal("MSG341", sku, line.getExpiryDate().format(DAY)));
            }
            if (plan.blockedCode() != null) {
                PickingGuide.Part all = guide.parts(order.getSourceStoreId(), List.of(line.getProductId()),
                        LocalDate.now(clock)).get(line.getProductId());
                return new Planned(plans, new Refusal("MSG339", sku, all == null ? 0 : all.sellableQty(),
                        needed.get(line.getProductId())));
            }
            plans.put(line.getId(), plan);
            available.put(line.getProductId(), SourceLots.less(left, plan));
        }
        return new Planned(plans, null);
    }
}
