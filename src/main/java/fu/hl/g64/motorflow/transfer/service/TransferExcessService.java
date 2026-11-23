package fu.hl.g64.motorflow.transfer.service;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.receiving.dto.ExcessDecisionRequest;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.sales.helper.PickingGuide;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.helper.SourceLots;
import fu.hl.g64.motorflow.transfer.helper.TransferExcessAcceptance;
import fu.hl.g64.motorflow.transfer.helper.SourceLots.Plan;
import fu.hl.g64.motorflow.transfer.helper.SourceLots.PlannedLot;
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

    private final TransferOrderRepository orders;
    private final ProductRepository products;
    private final GoodsReceiptNoteRepository receipts;
    private final GoodsReceiptNoteLineRepository receiptLines;
    private final TransferReceiptService transferReceipts;
    private final TransferExcessAcceptance acceptance;
    private final PickingGuide guide;
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
        // PM 23/11 case 3: transfer excess is always kept; the Owner accepts it early here or closing accepts it.
        if (request.lines().stream().anyMatch(choice -> choice.decision() != ExcessDecisionRequest.Choice.ACCEPTED)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG346");
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

        acceptance.accept(order, pending, owner.id(), "Owner duyệt hàng thừa điều chuyển");
        return transferReceipts.receipt(owner, orderId, receiptId);
    }

    private static Plan plan(GoodsReceiptNoteLine line, PickingGuide.Part available) {
        return SourceLots.plan(line.getExcessQty(), line.getExpiryDate(), available);
    }

    public record PendingReceipt(long receiptId, long transferOrderId, long sourceStoreId, long destStoreId,
                                 String sourceStoreName, String destStoreName, List<PendingLine> lines) {
    }

    /** {@code lots} is empty and {@code blockedCode} set when accepting now would be refused. */
    public record PendingLine(long lineId, long productId, String sku, String partName, int excessQty,
                              LocalDate expiryDate, List<PlannedLot> lots, String blockedCode) {
    }

}
