package fu.hl.g64.motorflow.transfer.service;

import fu.hl.g64.motorflow.account.entity.enums.Role;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TransferExcessService {

    private final TransferOrderRepository orders;
    private final GoodsReceiptNoteRepository receipts;
    private final GoodsReceiptNoteLineRepository receiptLines;
    private final TransferReceiptService transferReceipts;
    private final StockBalanceRepository balances;
    private final PickingGuide guide;
    private final InventoryPostingService posting;
    private final JdbcClient jdbc;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<PendingReceipt> pending(int page, int size) {
        int limit = Math.clamp(size, 1, 100);
        return jdbc.sql("""
                SELECT DISTINCT r.id, r.transfer_order_id, t.source_store_id, t.dest_store_id
                FROM goods_receipt_note r
                JOIN transfer_order t ON t.id = r.transfer_order_id
                JOIN goods_receipt_note_line l ON l.goods_receipt_note_id = r.id
                WHERE r.receipt_type = 'TRANSFER_IN' AND r.status = 'CONFIRMED'
                  AND l.excess_decision = 'AWAITING_OWNER'
                ORDER BY r.id DESC LIMIT :limit OFFSET :offset
                """).param("limit", limit).param("offset", (long) Math.max(page, 0) * limit)
                .query((rs, row) -> new PendingReceipt(rs.getLong("id"), rs.getLong("transfer_order_id"),
                        rs.getLong("source_store_id"), rs.getLong("dest_store_id"))).list();
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
            for (GoodsReceiptNoteLine line : accepted) {
                int quantity = line.getExcessQty();
                var available = availability.get(line.getProductId());
                if (available == null || available.sellableQty() < quantity) {
                    throw insufficient(line);
                }
                List<LotQuantity> selected = new ArrayList<>();
                int remaining = quantity;
                for (int i = 0; i < available.lotsInOrder().size(); i++) {
                    var lot = available.lotsInOrder().get(i);
                    if (!Objects.equals(lot.getExpiryDate(), line.getExpiryDate())) {
                        continue;
                    }
                    int take = Math.min(remaining, available.lotQuantities().get(i));
                    selected.add(new LotQuantity(lot.getId(), take));
                    remaining -= take;
                    if (remaining == 0) {
                        break;
                    }
                }
                if (remaining != 0) {
                    throw insufficient(line);
                }
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

    private static BusinessException insufficient(GoodsReceiptNoteLine line) {
        return new BusinessException(HttpStatus.CONFLICT, "MSG339", line.getProductId());
    }

    public record PendingReceipt(long receiptId, long transferOrderId, long sourceStoreId, long destStoreId) {
    }
}
