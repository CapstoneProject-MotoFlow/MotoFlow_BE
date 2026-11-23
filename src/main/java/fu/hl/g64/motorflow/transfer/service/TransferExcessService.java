package fu.hl.g64.motorflow.transfer.service;

import fu.hl.g64.motorflow.account.entity.enums.Role;
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
import fu.hl.g64.motorflow.receiving.dto.ExcessDecisionRequest;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.sales.helper.PickingGuide;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.dto.TransferReceiptResponse;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransferExcessService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final TransferOrderRepository orders;
    private final ProductRepository products;
    private final GoodsReceiptNoteRepository receipts;
    private final GoodsReceiptNoteLineRepository receiptLines;
    private final TransferReceiptService transferReceipts;
    private final StockBalanceRepository balances;
    private final PickingGuide guide;
    private final InventoryPostingService posting;
    private final JdbcClient jdbc;
    private final Clock clock;

    /**
     * Q7, review 73-6: the transfer receipts with excess awaiting the Owner, newest first, with the sending and
     * receiving warehouse and, per pending line, the source lots an acceptance would take now ({@code blockedCode}
     * MSG339 or MSG341 when it would be refused).
     */
    @Transactional(readOnly = true)
    public List<PendingReceipt> pending(int page, int size) {
        int limit = Math.clamp(size, 1, 100);
        record Row(long receiptId, long orderId, long sourceId, String sourceName, long destId, String destName) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT r.id, r.transfer_order_id, t.source_store_id, src.store_name AS source_name,
                       t.dest_store_id, dst.store_name AS dest_name
                FROM goods_receipt_note r
                JOIN transfer_order t ON t.id = r.transfer_order_id
                JOIN store src ON src.id = t.source_store_id
                JOIN store dst ON dst.id = t.dest_store_id
                WHERE r.receipt_type = 'TRANSFER_IN' AND r.status = 'CONFIRMED'
                  AND EXISTS (SELECT 1 FROM goods_receipt_note_line l
                              WHERE l.goods_receipt_note_id = r.id AND l.excess_decision = 'AWAITING_OWNER')
                ORDER BY r.id DESC LIMIT :limit OFFSET :offset
                """).param("limit", limit).param("offset", (long) Math.max(page, 0) * limit)
                .query((rs, row) -> new Row(rs.getLong("id"), rs.getLong("transfer_order_id"),
                        rs.getLong("source_store_id"), rs.getString("source_name"), rs.getLong("dest_store_id"),
                        rs.getString("dest_name"))).list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<GoodsReceiptNoteLine>> linesByReceipt = receiptLines.findByGoodsReceiptNoteIdInAndExcessDecision(
                        rows.stream().map(Row::receiptId).toList(), ExcessDecision.AWAITING_OWNER).stream()
                .collect(Collectors.groupingBy(GoodsReceiptNoteLine::getGoodsReceiptNoteId));
        Map<Long, Product> parts = products.findAllById(linesByReceipt.values().stream().flatMap(List::stream)
                        .map(GoodsReceiptNoteLine::getProductId).distinct().toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        LocalDate today = LocalDate.now(clock);
        Map<Long, Map<Long, PickingGuide.Part>> stockBySource = new HashMap<>();
        rows.stream().collect(Collectors.groupingBy(Row::sourceId)).forEach((source, ofSource) ->
                stockBySource.put(source, guide.parts(source, ofSource.stream()
                        .flatMap(row -> linesByReceipt.getOrDefault(row.receiptId(), List.of()).stream())
                        .map(GoodsReceiptNoteLine::getProductId).distinct().toList(), today)));
        return rows.stream().map(row -> new PendingReceipt(row.receiptId(), row.orderId(), row.sourceId(),
                row.destId(), row.sourceName(), row.destName(),
                linesByReceipt.getOrDefault(row.receiptId(), List.of()).stream().map(line -> {
                    Product part = parts.get(line.getProductId());
                    Plan plan = plan(line, stockBySource.get(row.sourceId()).get(line.getProductId()));
                    return new PendingLine(line.getId(), line.getProductId(), part.getSku(), part.getPartName(),
                            line.getExcessQty(), line.getExpiryDate(), plan.lots(), plan.blockedCode());
                }).toList())).toList();
    }

    @Transactional
    public TransferReceiptResponse decideExcess(AuthenticatedUser owner, long orderId, long receiptId,
                                               ExcessDecisionRequest request) {
        if (owner.role() != Role.OWNER) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        TransferOrder order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        var receipt = receipts.findByIdForUpdate(receiptId)
                .filter(r -> Objects.equals(r.getTransferOrderId(), orderId) && r.getReceiptType() == ReceiptType.TRANSFER_IN)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG70"));
        if (order.getStatus() != TransferOrderStatus.RECONCILING || receipt.getStatus() != GoodsReceiptNoteStatus.CONFIRMED) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }

        List<GoodsReceiptNoteLine> pending = receiptLines.findByGoodsReceiptNoteIdOrderByIdAsc(receiptId).stream()
                .filter(line -> line.getExcessDecision() == ExcessDecision.AWAITING_OWNER)
                .sorted(Comparator.comparing(GoodsReceiptNoteLine::getProductId)).toList();
        Map<Long, ExcessDecisionRequest.Choice> choices = new LinkedHashMap<>();
        for (var choice : request.lines()) {
            if (choices.putIfAbsent(choice.lineId(), choice.decision()) != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG02");
            }
        }
        if (pending.isEmpty() || choices.size() != pending.size()
                || pending.stream().anyMatch(line -> !choices.containsKey(line.getId()))) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }

        List<GoodsReceiptNoteLine> accepted = pending.stream()
                .filter(line -> choices.get(line.getId()) == ExcessDecisionRequest.Choice.ACCEPTED).toList();
        List<MovePosting> moves = new ArrayList<>();
        if (!accepted.isEmpty()) {
            List<Long> productIds = accepted.stream().map(GoodsReceiptNoteLine::getProductId).toList();
            long source = order.getSourceStoreId();
            long destination = order.getDestStoreId();
            balances.findByStoreForUpdate(Math.min(source, destination), productIds);
            balances.findByStoreForUpdate(Math.max(source, destination), productIds);
            LocalDate today = LocalDate.now(clock);
            Map<Long, PickingGuide.Part> availability = guide.parts(source, productIds, today);
            Map<Long, String> skus = products.findAllById(productIds).stream()
                    .collect(Collectors.toMap(Product::getId, Product::getSku));
            for (GoodsReceiptNoteLine line : accepted) {
                int quantity = line.getExcessQty();
                String sku = skus.get(line.getProductId());
                Plan plan = plan(line, availability.get(line.getProductId()));
                if ("MSG341".equals(plan.blockedCode())) {
                    throw new BusinessException(HttpStatus.CONFLICT, "MSG341", sku, line.getExpiryDate().format(DAY));
                }
                if (plan.blockedCode() != null) {
                    throw new BusinessException(HttpStatus.CONFLICT, "MSG339", sku);
                }
                List<LotQuantity> selected = plan.lots().stream()
                        .map(lot -> new LotQuantity(lot.lotId(), lot.quantity())).toList();
                moves.add(new MovePosting(line.getProductId(), source, StockStatus.AVAILABLE, destination,
                        StockStatus.AVAILABLE, quantity, selected, MovementType.TRANSFER_EXTRA_DISPATCH,
                        SourceRef.goodsReceiptNote(receiptId), owner.id(), "Owner duyệt hàng thừa điều chuyển"));
            }
            posting.moveAll(moves);
        }
        pending.forEach(line -> line.decideExcess(choices.get(line.getId()) == ExcessDecisionRequest.Choice.ACCEPTED
                ? ExcessDecision.ACCEPTED : ExcessDecision.REJECTED));
        receipt.recordExcessDecision(owner.id(), LocalDateTime.now(clock));
        return transferReceipts.receipt(owner, orderId, receiptId);
    }

    /**
     * Q7/B2, G3: the source lots that would cover the excess of {@code line} now. The lots of the expiry the
     * destination recorded; with no expiry recorded, oldest first. MSG341 when no sellable lot has that expiry (most
     * likely recorded wrong: blocked, not guessed), MSG339 when the source cannot cover it.
     */
    private static Plan plan(GoodsReceiptNoteLine line, PickingGuide.Part available) {
        int quantity = line.getExcessQty();
        if (available == null || available.sellableQty() < quantity) {
            return new Plan(List.of(), "MSG339");
        }
        if (line.getExpiryDate() != null && available.lotsInOrder().stream()
                .noneMatch(lot -> line.getExpiryDate().equals(lot.getExpiryDate()))) {
            return new Plan(List.of(), "MSG341");
        }
        List<PlannedLot> selected = new ArrayList<>();
        int remaining = quantity;
        for (int i = 0; i < available.lotsInOrder().size() && remaining > 0; i++) {
            var lot = available.lotsInOrder().get(i);
            if (line.getExpiryDate() != null && !Objects.equals(lot.getExpiryDate(), line.getExpiryDate())) {
                continue;
            }
            int take = Math.min(remaining, available.lotQuantities().get(i));
            if (take > 0) {
                selected.add(new PlannedLot(lot.getId(), lot.getExpiryDate(), take));
                remaining -= take;
            }
        }
        return remaining == 0 ? new Plan(selected, null) : new Plan(List.of(), "MSG339");
    }

    private record Plan(List<PlannedLot> lots, String blockedCode) {
    }

    public record PendingReceipt(long receiptId, long transferOrderId, long sourceStoreId, long destStoreId,
                                 String sourceStoreName, String destStoreName, List<PendingLine> lines) {
    }

    /** {@code lots} is empty and {@code blockedCode} set when accepting now would be refused. */
    public record PendingLine(long lineId, long productId, String sku, String partName, int excessQty,
                              LocalDate expiryDate, List<PlannedLot> lots, String blockedCode) {
    }

    public record PlannedLot(long lotId, LocalDate expiryDate, int quantity) {
    }
}
