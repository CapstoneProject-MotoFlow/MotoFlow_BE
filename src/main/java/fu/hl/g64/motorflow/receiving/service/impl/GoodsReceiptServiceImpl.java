package fu.hl.g64.motorflow.receiving.service.impl;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.partner.entity.Supplier;
import fu.hl.g64.motorflow.partner.repository.SupplierRepository;
import fu.hl.g64.motorflow.receiving.dto.AssignedReceiptResponse;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderRepository;
import fu.hl.g64.motorflow.receiving.service.GoodsReceiptService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GoodsReceiptServiceImpl implements GoodsReceiptService {

    private final WarehouseTaskRepository tasks;
    private final GoodsReceiptNoteRepository receipts;
    private final GoodsReceiptNoteLineRepository receiptLines;
    private final PurchaseOrderRepository orders;
    private final SupplierRepository suppliers;

    // UC-39 (Staff)
    @Transactional(readOnly = true)
    @Override
    public PageResponse<AssignedReceiptResponse> assignedToMe(AuthenticatedUser user, int page, int size) {
        Page<WarehouseTask> found = tasks.findOpenSupplierInspections(user.id(), PageRequest.of(Math.max(page, 0),
                Math.clamp(size, 1, 100), Sort.by("assignedAt", "id")));
        if (found.isEmpty()) {
            return PageResponse.of(found, List.of());
        }
        List<Long> receiptIds = found.stream().map(WarehouseTask::getGoodsReceiptNoteId).toList();
        Map<Long, GoodsReceiptNote> receiptById = receipts.findAllById(receiptIds).stream()
                .collect(Collectors.toMap(GoodsReceiptNote::getId, Function.identity()));
        Map<Long, PurchaseOrder> orderById = orders.findAllById(receiptById.values().stream()
                        .map(GoodsReceiptNote::getPurchaseOrderId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(PurchaseOrder::getId, Function.identity()));
        Map<Long, String> supplierNames = suppliers.findAllById(orderById.values().stream()
                        .map(PurchaseOrder::getSupplierId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Supplier::getId, Supplier::getSupplierName));
        Map<Long, Long> lineCounts = receiptLines.countByReceipt(receiptIds).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));

        return PageResponse.of(found, found.stream().map(task -> {
            GoodsReceiptNote receipt = receiptById.get(task.getGoodsReceiptNoteId());
            PurchaseOrder order = orderById.get(receipt.getPurchaseOrderId());
            return new AssignedReceiptResponse(receipt.getId(), order.getId(), supplierNames.get(order.getSupplierId()),
                    receipt.getDeliveryDocumentNo(), receipt.getDeliveryDocumentDate(), receipt.getArrivedAt(),
                    receipt.getStatus(), lineCounts.getOrDefault(receipt.getId(), 0L), task.getStatus(),
                    task.getAssignedAt());
        }).toList());
    }
}
