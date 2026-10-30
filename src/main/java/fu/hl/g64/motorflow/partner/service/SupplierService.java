package fu.hl.g64.motorflow.partner.service;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.partner.dto.SupplierDetailResponse;
import fu.hl.g64.motorflow.partner.dto.SupplierRequest;
import fu.hl.g64.motorflow.partner.dto.SupplierResponse;
import fu.hl.g64.motorflow.partner.entity.Supplier;
import fu.hl.g64.motorflow.partner.entity.enums.SupplierStatus;
import fu.hl.g64.motorflow.partner.repository.SupplierRepository;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceipt;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptRequest;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptRequestLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptRequestRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** UC-30 View Supplier List, UC-31 Create Supplier, UC-32 View Supplier Detail, UC-33 Update Supplier (Owner). */
@Service
@RequiredArgsConstructor
public class SupplierService {

    /** Rows of each history list on the detail screen; older ones are counted but not listed. */
    public static final int HISTORY_SIZE = 20;

    private final SupplierRepository suppliers;
    private final GoodsReceiptRequestRepository purchaseOrders;
    private final GoodsReceiptRequestLineRepository purchaseOrderLines;
    private final GoodsReceiptRepository deliveries;
    private final DiscrepancyCaseRepository discrepancies;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final AuditLogRepository auditLog;
    private final Clock clock;

    /** By code. Every filter is optional; {@code q} matches code, name or phone number (spaces in phones are ignored). */
    @Transactional(readOnly = true)
    public PageResponse<SupplierResponse> list(String q, SupplierStatus status, int page, int size) {
        Page<Supplier> found = suppliers.findAll(matching(q, status),
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by("supplierCode")));
        return PageResponse.of(found, found.map(SupplierResponse::from).getContent());
    }

    @Transactional(readOnly = true)
    public SupplierDetailResponse detail(long id) {
        Supplier supplier = find(id);
        Pageable newest = PageRequest.of(0, HISTORY_SIZE, Sort.by(Sort.Direction.DESC, "id"));
        Page<GoodsReceiptRequest> orders = purchaseOrders.findBySupplierId(id, newest);
        Page<GoodsReceipt> received = deliveries.findBySupplier(id, newest);
        Page<DiscrepancyCase> cases = discrepancies.findBySupplier(id, newest);

        return new SupplierDetailResponse(SupplierResponse.from(supplier),
                purchaseOrderRows(orders.getContent()), orders.getTotalElements(),
                received.map(r -> new SupplierDetailResponse.Delivery(r.getId(), r.getGoodsReceiptRequestId(),
                        r.getDeliveryDocumentNo(), r.getStatus(), r.getArrivedAt(), r.getConfirmedAt())).getContent(),
                received.getTotalElements(),
                discrepancyRows(cases.getContent()), cases.getTotalElements(),
                discrepancies.countBySupplierAndStatus(id, DiscrepancyCaseStatus.OPEN));
    }

    /** UC-31. A new supplier is active. */
    @Transactional
    public SupplierResponse create(AuthenticatedUser actor, SupplierRequest request) {
        if (!StringUtils.hasText(request.supplierCode())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG02");
        }
        String code = request.supplierCode().strip().toUpperCase(Locale.ROOT);
        if (suppliers.existsBySupplierCode(code)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG72", code);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Supplier supplier = Supplier.create(code, request.supplierName().strip(), blankToNull(request.contactName()),
                blankToNull(request.phone()), blankToNull(request.email()), blankToNull(request.address()),
                blankToNull(request.taxCode()), now);
        try {
            supplier = suppliers.saveAndFlush(supplier);
        } catch (DataIntegrityViolationException e) {
            // Another Owner session created the same code a moment earlier.
            throw new BusinessException(HttpStatus.CONFLICT, "MSG72", code);
        }
        auditLog.save(AuditLog.record("SUPPLIER", supplier.getId(), "SUPPLIER_CREATED", actor.id(), null, now));
        return SupplierResponse.from(supplier);
    }

    /** UC-33. The supplier code never changes after creation (Data Business Rules: unique supplier code). */
    @Transactional
    public SupplierResponse update(AuthenticatedUser actor, long id, SupplierRequest request) {
        Supplier supplier = find(id);
        LocalDateTime now = LocalDateTime.now(clock);
        supplier.update(request.supplierName().strip(), blankToNull(request.contactName()), blankToNull(request.phone()),
                blankToNull(request.email()), blankToNull(request.address()), blankToNull(request.taxCode()), now);
        auditLog.save(AuditLog.record("SUPPLIER", id, "SUPPLIER_UPDATED", actor.id(), null, now));
        return SupplierResponse.from(supplier);
    }

    /** Part of UC-33. Purchase orders, deliveries and discrepancies of the supplier are untouched. */
    @Transactional
    public void changeStatus(AuthenticatedUser actor, long id, SupplierStatus status) {
        Supplier supplier = find(id);
        if (supplier.getStatus() == status) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        supplier.changeStatus(status, now);
        String action = status == SupplierStatus.ACTIVE ? "SUPPLIER_ACTIVATED" : "SUPPLIER_DEACTIVATED";
        auditLog.save(AuditLog.record("SUPPLIER", id, action, actor.id(), null, now));
    }

    private List<SupplierDetailResponse.PurchaseOrder> purchaseOrderRows(List<GoodsReceiptRequest> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, Object[]> totals = purchaseOrderLines.summarizeByRequest(orders.stream().map(GoodsReceiptRequest::getId).toList())
                .stream().collect(Collectors.toMap(row -> (Long) row[0], Function.identity()));
        Map<Long, String> storeNames = stores.findAllById(orders.stream().map(GoodsReceiptRequest::getStoreId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Store::getId, Store::getStoreName));

        return orders.stream().map(order -> {
            Object[] total = totals.get(order.getId());
            return new SupplierDetailResponse.PurchaseOrder(order.getId(), order.getStoreId(),
                    storeNames.get(order.getStoreId()), order.getStatus(), order.getExpectedArrival(),
                    total == null ? 0 : (Long) total[1], total == null ? 0 : ((Number) total[2]).longValue(),
                    order.getCreatedAt());
        }).toList();
    }

    private List<SupplierDetailResponse.Discrepancy> discrepancyRows(List<DiscrepancyCase> cases) {
        Map<Long, Product> productById = products.findAllById(cases.stream().map(DiscrepancyCase::getProductId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        return cases.stream().map(c -> {
            Product product = productById.get(c.getProductId());
            return new SupplierDetailResponse.Discrepancy(c.getId(), c.getGoodsReceiptId(), c.getProductId(),
                    product.getSku(), product.getPartName(), c.getShortQty(), c.getWrongItemQty(), c.getDamagedQty(),
                    c.getRejectedExcessQty(), c.getStatus(), c.getOpenedAt(), c.getClosedAt());
        }).toList();
    }

    private static Specification<Supplier> matching(String q, SupplierStatus status) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (StringUtils.hasText(q)) {
                String needle = q.strip().toLowerCase(Locale.ROOT);
                String like = "%" + needle + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("supplierCode")), like),
                        cb.like(cb.lower(root.get("supplierName")), like),
                        cb.like(cb.function("replace", String.class, root.get("phone"), cb.literal(" "), cb.literal("")),
                                "%" + needle.replace(" ", "") + "%")));
            }
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private Supplier find(long id) {
        return suppliers.findById(id).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG73"));
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
