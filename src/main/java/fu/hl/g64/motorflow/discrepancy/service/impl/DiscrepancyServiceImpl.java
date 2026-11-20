package fu.hl.g64.motorflow.discrepancy.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.ProductIdentifier;
import fu.hl.g64.motorflow.catalog.entity.ProductVehicleModel;
import fu.hl.g64.motorflow.catalog.entity.enums.IdentifierType;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductIdentifierRepository;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.catalog.repository.ProductVehicleModelRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.common.text.SearchText;
import fu.hl.g64.motorflow.discrepancy.dto.AcceptAsVariantRequest;
import fu.hl.g64.motorflow.discrepancy.dto.AcceptanceRequest;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyCaseResponse;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyCaseResponse.Event;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyHandlingRequest;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancySearch;
import fu.hl.g64.motorflow.discrepancy.dto.ReceiveWrongItemsRequest;
import fu.hl.g64.motorflow.discrepancy.dto.RejectAcceptanceRequest;
import fu.hl.g64.motorflow.discrepancy.dto.SupplierReturnRequest;
import fu.hl.g64.motorflow.discrepancy.dto.WaiveReplacementRequest;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.discrepancy.entity.enums.SourceType;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement.Progress;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement.Standing;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.discrepancy.service.DiscrepancyService;
import fu.hl.g64.motorflow.inventory.dto.LotQuantity;
import fu.hl.g64.motorflow.inventory.dto.MovePosting;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.dto.OutPosting;
import fu.hl.g64.motorflow.inventory.entity.Lot;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.helper.DefectiveArea;
import fu.hl.g64.motorflow.inventory.repository.LotRepository;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.partner.entity.Supplier;
import fu.hl.g64.motorflow.partner.repository.SupplierRepository;
import fu.hl.g64.motorflow.pricing.dto.RetailPriceRequest;
import fu.hl.g64.motorflow.pricing.repository.PricingPolicyRepository;
import fu.hl.g64.motorflow.pricing.service.RetailPriceService;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
import fu.hl.g64.motorflow.receiving.helper.PurchaseOrderCode;
import fu.hl.g64.motorflow.receiving.helper.ReceivingLedger;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DiscrepancyServiceImpl implements DiscrepancyService {

    /** audit_log.entity_type and action of a saved handling (UC-46 step 7). */
    private static final String AUDIT_ENTITY = "DISCREPANCY_CASE";
    private static final String HANDLING_SAVED = "DISCREPANCY_HANDLING_SAVED";
    /** GVHD 20/11 (PR 5): the request to take held goods into stock and Owner's answer, kept in audit_log. */
    private static final String ACCEPTANCE_REQUESTED = "DISCREPANCY_ACCEPTANCE_REQUESTED";
    private static final String ACCEPTANCE_REJECTED = "DISCREPANCY_ACCEPTANCE_REJECTED";
    private static final String ACCEPTANCE_APPROVED = "DISCREPANCY_ACCEPTANCE_APPROVED";
    private static final List<String> ACCEPTANCE_ACTIONS = List.of(ACCEPTANCE_REQUESTED, ACCEPTANCE_REJECTED,
            ACCEPTANCE_APPROVED);
    private static final String REF_TYPE = "DISCREPANCY_CASE";

    private final DiscrepancyCaseRepository discrepancies;
    private final DiscrepancyResolutionEventRepository events;
    private final GoodsReceiptNoteRepository receipts;
    private final GoodsReceiptNoteLineRepository receiptLines;
    private final PurchaseOrderRepository orders;
    private final SupplierRepository suppliers;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final UserAccountRepository users;
    private final LotRepository lots;
    private final InventoryPostingService posting;
    private final AttachmentService attachments;
    private final AuditLogRepository auditLog;
    private final ReceivingLedger ledger;
    private final DiscrepancySettlement settlement;
    private final ProductIdentifierRepository identifiers;
    private final ProductVehicleModelRepository vehicles;
    private final RetailPriceService retailPrices;
    private final PricingPolicyRepository pricingPolicies;
    private final StockBalanceRepository balances;
    private final NotificationService notifications;
    private final Clock clock;

    @Transactional(readOnly = true)
    @Override
    public PageResponse<DiscrepancyCaseResponse> list(AuthenticatedUser user, DiscrepancySearch search, int page,
                                                      int size) {
        Long storeId = search.storeId();
        if (user.role() != Role.OWNER) {
            if (storeId != null && !storeId.equals(user.storeId())) {
                throw forbidden();
            }
            storeId = user.storeId();
        }
        Page<DiscrepancyCase> found = discrepancies.findAll(matching(storeId, search), PageRequest.of(
                Math.max(page, 0), Math.clamp(size, 1, 100),
                Sort.by(Sort.Order.desc("openedAt"), Sort.Order.desc("id"))));
        return PageResponse.of(found, responses(user, found.getContent(), false));
    }

    @Transactional(readOnly = true)
    @Override
    public DiscrepancyCaseResponse get(AuthenticatedUser user, long id) {
        DiscrepancyCase found = discrepancies.findById(id)
                .filter(c -> c.getSourceType() == SourceType.SUPPLIER)
                .orElseThrow(DiscrepancyServiceImpl::notFound);
        requireHandler(user, orderOf(found.getGoodsReceiptNoteId()));
        return responses(user, List.of(found), true).getFirst();
    }

    // UC-46 steps 3-7
    @Transactional
    @Override
    public List<DiscrepancyCaseResponse> saveHandling(AuthenticatedUser handler, long receiptId,
                                                      DiscrepancyHandlingRequest request) {
        GoodsReceiptNote receipt = receipts.findById(receiptId)
                .filter(r -> r.getReceiptType() == ReceiptType.SUPPLIER_DELIVERY)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG132"));
        PurchaseOrder order = orders.findById(receipt.getPurchaseOrderId()).orElseThrow();
        requireHandler(handler, order);
        // Lock order: cases, then stock.
        List<DiscrepancyCase> cases = discrepancies.findByReceiptForUpdate(receiptId);
        Map<Long, DiscrepancyCase> caseById = byId(cases, DiscrepancyCase::getId);
        Map<Long, Progress> progress = settlement.progress(caseById.keySet());
        Map<Long, Agreed> wanted = new LinkedHashMap<>();
        for (int i = 0; i < request.lines().size(); i++) {
            DiscrepancyHandlingRequest.Line line = request.lines().get(i);
            int n = i + 1;
            DiscrepancyCase found = caseById.get(line.caseId());
            if (found == null || wanted.containsKey(found.getId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG136", n);
            }
            if (!found.isOpen()) {
                throw changed();
            }
            // UC-46 Step 5_Invalid or stale quantities. Test report 20/11: the return may come down again (the store
            // keeps the goods) but never below what went back already or the excess Owner rejected.
            Progress done = progress.getOrDefault(found.getId(), Progress.NONE);
            // GVHD 20/11: goods Owner took into stock as another part are no longer held.
            if (line.returnRequiredQty() > found.heldQty() - done.acceptedDamaged() - done.acceptedWrong()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG35", n);
            }
            int returnFloor = returnFloor(found, progress.getOrDefault(found.getId(), Progress.NONE));
            if (line.returnRequiredQty() < returnFloor) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG134", n, returnFloor);
            }
            // UC-46 step 3, BR-27: the replacement is agreed apart from the return, never above the case's limit
            // (BR-67); left empty, it grows by the wrong or damaged units newly agreed to go back.
            Integer asked = line.replacementRequiredQty();
            if (asked != null && asked > found.replacementLimit()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG35", n);
            }
            int replacement = asked != null ? asked : found.defaultReplacement(line.returnRequiredQty());
            int floor = replacementFloor(found, progress.get(found.getId()));
            if (replacement < floor) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG193", n, floor);
            }
            wanted.put(found.getId(), new Agreed(line.returnRequiredQty(), replacement));
        }

        String note = text(request.note());
        LocalDateTime now = LocalDateTime.now(clock);
        List<DiscrepancyCase> handled = cases.stream().filter(found -> wanted.containsKey(found.getId()))
                .sorted(Comparator.comparing(DiscrepancyCase::getProductId)).toList();
        for (DiscrepancyCase found : handled) {
            int before = found.getReturnRequiredQty();
            Agreed agreed = wanted.get(found.getId());
            int after = agreed.returnQty();
            if (after == before && agreed.replacementQty() == found.getReplacementRequiredQty()) {
                continue;
            }
            // Rejected excess is already awaiting return (UC-45); the rest comes from the wrong items first, then
            // from the damaged goods.
            int movedBefore = before - found.getRejectedExcessQty();
            int movedAfter = after - found.getRejectedExcessQty();
            int wrongBefore = Math.min(movedBefore, found.getWrongItemQty());
            int wrongAfter = Math.min(movedAfter, found.getWrongItemQty());
            long lotId = lotOf(receiptId, found.getProductId());
            hold(found, StockStatus.WRONG_ITEM, wrongAfter - wrongBefore, lotId, order.getStoreId(), handler.id(),
                    note, now);
            hold(found, StockStatus.DAMAGED, (movedAfter - wrongAfter) - (movedBefore - wrongBefore), lotId,
                    order.getStoreId(), handler.id(), note, now);
            Map<String, Object> old = obligations(found);
            found.agree(after, agreed.replacementQty());
            auditLog.save(AuditLog.record(AUDIT_ENTITY, found.getId(), HANDLING_SAVED, handler.id(), note, old,
                    obligations(found), now));
        }
        handled.forEach(found -> settlement.closeIfSettled(found, handler.id(), now));
        return responses(handler, cases, true);
    }

    // UC-46 Step 1_Actual supplier return
    @Transactional
    @Override
    public DiscrepancyCaseResponse recordSupplierReturn(AuthenticatedUser manager, long id,
                                                        SupplierReturnRequest request) {
        long receiptId = discrepancies.findReceiptIdOf(id).orElseThrow(DiscrepancyServiceImpl::notFound);
        PurchaseOrder order = orderOf(receiptId);
        // Actual returns are recorded only by the receiving Branch Manager (UC-46 preconditions).
        if (manager.role() != Role.BRANCH_MANAGER || !Objects.equals(manager.storeId(), order.getStoreId())) {
            throw forbidden();
        }
        DiscrepancyCase found = discrepancies.findAllByIdForUpdate(List.of(id)).getFirst();
        if (!found.isOpen()) {
            throw changed();
        }
        int left = settlement.standing(found).outstandingReturn();
        if (left == 0) {
            throw changed();
        }
        if (request.quantity() > left) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG135", left);
        }
        int quantity = request.quantity();
        String note = text(request.note());
        LocalDateTime now = LocalDateTime.now(clock);
        DiscrepancyResolutionEvent event = events.save(DiscrepancyResolutionEvent.done(id,
                ResolutionType.RETURNED_TO_SUPPLIER, quantity, note, manager.id(), now));
        // The goods leave the store from awaiting return to supplier, valued at the average (BR-74).
        long lotId = lotOf(receiptId, found.getProductId());
        posting.takeOut(new OutPosting(order.getStoreId(), found.getProductId(), StockStatus.AWAITING_SUPPLIER_RETURN,
                quantity, List.of(new LotQuantity(lotId, quantity)), MovementType.SUPPLIER_RETURN,
                SourceRef.discrepancyResolutionEvent(event.getId()), manager.id(), note));
        attachments.attach(OwnerType.DISCREPANCY_RESOLUTION_EVENT, event.getId(),
                request.evidence() == null ? List.of() : request.evidence(), manager.id());
        settlement.closeIfSettled(found, manager.id(), now);
        return responses(manager, List.of(found), true).getFirst();
    }

    // UC-46 Step 3_Shortage settled without replacement
    @Transactional
    @Override
    public DiscrepancyCaseResponse waiveReplacement(AuthenticatedUser owner, long id,
                                                    WaiveReplacementRequest request) {
        if (owner.role() != Role.OWNER) {
            throw forbidden();
        }
        discrepancies.findReceiptIdOf(id).orElseThrow(DiscrepancyServiceImpl::notFound);
        DiscrepancyCase found = discrepancies.findAllByIdForUpdate(List.of(id)).getFirst();
        if (!found.isOpen()) {
            throw changed();
        }
        // Replacement a replacement delivery is already bringing is settled by that delivery's confirmation.
        int open = settlement.standing(found).openReplacement();
        if (open == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG141", "#" + id);
        }
        int quantity = request.quantity() == null ? open : request.quantity();
        if (quantity > open) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG144", open);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        events.save(DiscrepancyResolutionEvent.done(id, ResolutionType.CLOSED_BY_AGREEMENT, quantity,
                request.reason().strip(), owner.id(), now));
        settlement.closeIfSettled(found, owner.id(), now);
        return responses(owner, List.of(found), true).getFirst();
    }

    // GVHD 20/11 (PR 5): a Branch Manager asks Owner to take held goods into stock
    @Transactional
    @Override
    public DiscrepancyCaseResponse requestAcceptance(AuthenticatedUser user, long id, AcceptanceRequest request) {
        long receiptId = discrepancies.findReceiptIdOf(id).orElseThrow(DiscrepancyServiceImpl::notFound);
        PurchaseOrder order = orderOf(receiptId);
        requireHandler(user, order);
        DiscrepancyCase found = discrepancies.findAllByIdForUpdate(List.of(id)).getFirst();
        if (!found.isOpen()) {
            throw changed();
        }
        Standing standing = settlement.standing(found);
        boolean damaged = request.type() == AcceptanceRequest.Type.DAMAGED;
        int undecided = damaged ? standing.damagedUndecided() : standing.wrongUndecided();
        if (request.quantity() > undecided) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG309", undecided);
        }
        Product received = null;
        if (!damaged && request.productId() != null) {
            received = receivedPart(found, request.productId());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", request.type().name());
        data.put("product_id", received == null ? null : received.getId());
        data.put("quantity", request.quantity());
        data.put("note", text(request.note()));
        auditLog.save(AuditLog.record(AUDIT_ENTITY, id, ACCEPTANCE_REQUESTED, user.id(), text(request.note()), null,
                data, now));
        Product part = products.findById(found.getProductId()).orElseThrow();
        String what = damaged ? "%d %s bị móp làm hàng móp".formatted(request.quantity(), part.getSku())
                : "%d hàng giao sai thay cho %s%s".formatted(request.quantity(), part.getSku(),
                received == null ? "" : " là " + received.getSku());
        notifications.notifyOwners("DISCREPANCY_ACCEPTANCE_REQUESTED",
                "Sai lệch #%d của đơn nhập hàng %s: đề nghị nhận %s vào kho.".formatted(id,
                        PurchaseOrderCode.of(order.getId()), what), REF_TYPE, id);
        return responses(user, List.of(found), true).getFirst();
    }

    // GVHD 20/11 (PR 5)
    @Transactional
    @Override
    public DiscrepancyCaseResponse rejectAcceptance(AuthenticatedUser owner, long id, RejectAcceptanceRequest request) {
        requireOwner(owner);
        long receiptId = discrepancies.findReceiptIdOf(id).orElseThrow(DiscrepancyServiceImpl::notFound);
        PurchaseOrder order = orderOf(receiptId);
        DiscrepancyCase found = discrepancies.findAllByIdForUpdate(List.of(id)).getFirst();
        if (!found.isOpen() || !pendingRequests(List.of(id)).containsKey(id)) {
            throw changed();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String reason = request.reason().strip();
        auditLog.save(AuditLog.record(AUDIT_ENTITY, id, ACCEPTANCE_REJECTED, owner.id(), reason, null,
                Map.of("reason", reason), now));
        notifications.notifyStoreManagers(order.getStoreId(), "DISCREPANCY_ACCEPTANCE_REJECTED",
                "Owner không nhận hàng của sai lệch #%d vào kho: %s. Vui lòng trả nhà cung cấp hoặc đề nghị ghi giảm."
                        .formatted(id, reason), REF_TYPE, id);
        return responses(owner, List.of(found), true).getFirst();
    }

    // GVHD 20/11 (PR 5)
    @Transactional
    @Override
    public DiscrepancyCaseResponse acceptAsVariant(AuthenticatedUser owner, long id, AcceptAsVariantRequest request) {
        requireOwner(owner);
        long receiptId = discrepancies.findReceiptIdOf(id).orElseThrow(DiscrepancyServiceImpl::notFound);
        PurchaseOrder order = orderOf(receiptId);
        DiscrepancyCase found = discrepancies.findAllByIdForUpdate(List.of(id)).getFirst();
        if (!found.isOpen()) {
            throw changed();
        }
        int undecided = settlement.standing(found).damagedUndecided();
        if (request.quantity() > undecided) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG309", undecided);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Product original = products.findById(found.getProductId()).orElseThrow();
        Product variant = variantOf(original, owner.id(), now);
        if (request.salePrice() != null) {
            retailPrices.update(owner, new RetailPriceRequest(variant.getId(), request.salePrice(),
                    now.toLocalDate(), "Giá hàng móp, sai lệch #" + id, false));
        } else if (pricingPolicies.findRetailPricesInEffect(List.of(variant.getId()), now).isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG311", original.getSku());
        }
        long lotId = lotOf(receiptId, found.getProductId());
        Lot lot = lots.findById(lotId).orElseThrow();
        String note = "Nhận hàng móp thành " + variant.getSku();
        DiscrepancyResolutionEvent event = events.save(DiscrepancyResolutionEvent.done(id,
                ResolutionType.ACCEPTED_AS_VARIANT, request.quantity(), note, owner.id(), now));
        SourceRef source = SourceRef.discrepancyResolutionEvent(event.getId());
        posting.takeOut(new OutPosting(order.getStoreId(), original.getId(), StockStatus.DAMAGED, request.quantity(),
                List.of(new LotQuantity(lotId, request.quantity())), MovementType.DAMAGED_TO_VARIANT, source,
                owner.id(), note));
        posting.receiveNewLot(new NewLotPosting(order.getStoreId(), variant.getId(), LotSourceType.DISCREPANCY, null,
                order.getSupplierId(), now.toLocalDate(), request.unitCost(), lot.getManufacturerLotNo(),
                lot.getManufactureDate(), lot.getExpiryDate(), Map.of(StockStatus.AVAILABLE, request.quantity()),
                MovementType.DAMAGED_TO_VARIANT, source, owner.id(), note));
        // The variant is shelved in the defective goods area.
        balances.findByStoreForUpdate(order.getStoreId(), List.of(variant.getId())).forEach(balance -> {
            if (balance.getStorageArea() == null) {
                balance.moveTo(DefectiveArea.NAME, balance.getBinLocation(), now);
            }
        });
        approved(owner, found, "DAMAGED", variant.getId(), request.quantity(), now);
        settlement.closeIfSettled(found, owner.id(), now);
        return responses(owner, List.of(found), true).getFirst();
    }

    // GVHD 20/11 (PR 5)
    @Transactional
    @Override
    public DiscrepancyCaseResponse receiveWrongItems(AuthenticatedUser owner, long id,
                                                     ReceiveWrongItemsRequest request) {
        requireOwner(owner);
        long receiptId = discrepancies.findReceiptIdOf(id).orElseThrow(DiscrepancyServiceImpl::notFound);
        PurchaseOrder order = orderOf(receiptId);
        DiscrepancyCase found = discrepancies.findAllByIdForUpdate(List.of(id)).getFirst();
        if (!found.isOpen()) {
            throw changed();
        }
        int undecided = settlement.standing(found).wrongUndecided();
        if (request.quantity() > undecided) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG309", undecided);
        }
        Product received = receivedPart(found, request.productId());
        LocalDateTime now = LocalDateTime.now(clock);
        long lotId = lotOf(receiptId, found.getProductId());
        String note = "Nhận hàng giao sai thành " + received.getSku();
        DiscrepancyResolutionEvent event = events.save(DiscrepancyResolutionEvent.done(id,
                ResolutionType.RECEIVED_AS_OTHER_PRODUCT, request.quantity(), note, owner.id(), now));
        SourceRef source = SourceRef.discrepancyResolutionEvent(event.getId());
        posting.takeOut(new OutPosting(order.getStoreId(), found.getProductId(), StockStatus.WRONG_ITEM,
                request.quantity(), List.of(new LotQuantity(lotId, request.quantity())),
                MovementType.WRONG_ITEM_RECLASSIFIED, source, owner.id(), note));
        posting.receiveNewLot(new NewLotPosting(order.getStoreId(), received.getId(), LotSourceType.DISCREPANCY, null,
                order.getSupplierId(), now.toLocalDate(), request.unitCost(), null, null, null,
                Map.of(StockStatus.AVAILABLE, request.quantity()), MovementType.WRONG_ITEM_RECLASSIFIED, source,
                owner.id(), note));
        if (!request.replacementNeeded()) {
            // As the Owner's waiver: the replacement still owed for these units is not to come.
            int waived = Math.min(request.quantity(), settlement.standing(found).openReplacement());
            if (waived > 0) {
                events.save(DiscrepancyResolutionEvent.done(id, ResolutionType.CLOSED_BY_AGREEMENT, waived,
                        "Đã nhận hàng giao sai, không cần giao bù", owner.id(), now));
            }
        }
        approved(owner, found, "WRONG_ITEM", received.getId(), request.quantity(), now);
        settlement.closeIfSettled(found, owner.id(), now);
        return responses(owner, List.of(found), true).getFirst();
    }

    /** The part goods delivered by mistake really are: on sale (MSG313) and not the part ordered (MSG310). */
    private Product receivedPart(DiscrepancyCase found, long productId) {
        Product received = products.findById(productId)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG57"));
        if (received.getId().equals(found.getProductId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG310",
                    products.findById(found.getProductId()).orElseThrow().getSku());
        }
        if (received.getStatus() != ProductStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG313", received.getSku());
        }
        return received;
    }

    /**
     * The dented-goods variant of {@code original}, made the first time (BR GVHD 20/11): SKU "<SKU>-M" (MSG312 when
     * another part has it), name "<name> (hàng móp)", the original's details, its OEM codes and vehicles, no barcode
     * so that a scan never finds it instead of the original.
     */
    private Product variantOf(Product original, long by, LocalDateTime now) {
        Optional<Product> existing = products.findByVariantOfProductId(original.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        String sku = original.getSku() + Product.VARIANT_SKU_SUFFIX;
        if (products.existsBySku(sku)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG312", sku);
        }
        Product variant = products.saveAndFlush(Product.damagedVariantOf(original, by, now));
        identifiers.saveAll(identifiers.findByProductIdIn(List.of(original.getId())).stream()
                .filter(code -> code.getIdentifierType() == IdentifierType.OEM)
                .map(code -> ProductIdentifier.of(variant.getId(), IdentifierType.OEM, code.getIdentifierValue()))
                .toList());
        vehicles.saveAll(vehicles.findByProductIdIn(List.of(original.getId())).stream()
                .map(link -> ProductVehicleModel.of(variant.getId(), link.getVehicleModelId())).toList());
        auditLog.save(AuditLog.record("PRODUCT", variant.getId(), "PRODUCT_CREATED", by,
                "Hàng móp của " + original.getSku(), now));
        return variant;
    }

    /** The request waiting for Owner is answered: Owner took the goods. */
    private void approved(AuthenticatedUser owner, DiscrepancyCase found, String type, long productId, int quantity,
                          LocalDateTime now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        data.put("product_id", productId);
        data.put("quantity", quantity);
        auditLog.save(AuditLog.record(AUDIT_ENTITY, found.getId(), ACCEPTANCE_APPROVED, owner.id(), null, null, data,
                now));
    }

    private static DiscrepancyCaseResponse.PendingRequest pendingRequest(AuditLog step, Map<Long, Product> parts,
                                                                         Map<Long, String> names) {
        if (step == null) {
            return null;
        }
        Map<String, Object> data = step.getNewData();
        Long productId = data.get("product_id") == null ? null : ((Number) data.get("product_id")).longValue();
        Product part = productId == null ? null : parts.get(productId);
        return new DiscrepancyCaseResponse.PendingRequest((String) data.get("type"), productId,
                part == null ? null : part.getSku(), part == null ? null : part.getPartName(),
                ((Number) data.get("quantity")).intValue(), (String) data.get("note"), step.getActorId(),
                names.get(step.getActorId()), step.getOccurredAt());
    }

    /** [case id → its latest acceptance request] of the cases whose latest acceptance step is a request. */
    private Map<Long, AuditLog> pendingRequests(Collection<Long> caseIds) {
        Map<Long, AuditLog> latest = new HashMap<>();
        auditLog.findByEntityTypeAndEntityIdInAndActionIn(AUDIT_ENTITY, caseIds, ACCEPTANCE_ACTIONS)
                .forEach(step -> latest.merge(step.getEntityId(), step, (a, b) ->
                        Comparator.comparing(AuditLog::getOccurredAt).thenComparing(AuditLog::getId)
                                .compare(a, b) >= 0 ? a : b));
        latest.values().removeIf(step -> !ACCEPTANCE_REQUESTED.equals(step.getAction()));
        return latest;
    }

    private static void requireOwner(AuthenticatedUser user) {
        if (user.role() != Role.OWNER) {
            throw forbidden();
        }
    }

    /**
     * Moves goods of the case to awaiting return to supplier ({@code quantity} above 0), or back from it to the status
     * they were held from (below 0), with the event the movements point to.
     */
    private void hold(DiscrepancyCase found, StockStatus from, int quantity, long lotId, long storeId, long by,
                      String note, LocalDateTime now) {
        if (quantity == 0) {
            return;
        }
        boolean release = quantity < 0;
        int moved = Math.abs(quantity);
        DiscrepancyResolutionEvent event = events.save(DiscrepancyResolutionEvent.done(found.getId(),
                release ? ResolutionType.RELEASED_FROM_AWAITING_RETURN : ResolutionType.MOVED_TO_AWAITING_RETURN,
                moved, note, by, now));
        posting.move(new MovePosting(found.getProductId(), storeId,
                release ? StockStatus.AWAITING_SUPPLIER_RETURN : from, storeId,
                release ? from : StockStatus.AWAITING_SUPPLIER_RETURN, moved, List.of(new LotQuantity(lotId, moved)),
                release ? MovementType.RETURN_RELEASE : MovementType.RETURN_HOLD,
                SourceRef.discrepancyResolutionEvent(event.getId()), by, note));
    }

    /** The least the agreed return may be: what went back already, and the excess Owner rejected (UC-45). */
    private static int returnFloor(DiscrepancyCase found, Progress done) {
        return Math.max(found.getRejectedExcessQty(), done.returned());
    }

    /** The lot the receipt line of the part created; held, rejected and returned goods all stay in it. */
    private long lotOf(long receiptId, long productId) {
        List<Long> lineIds = receiptLines.findByGoodsReceiptNoteIdOrderByIdAsc(receiptId).stream()
                .filter(line -> line.getProductId() == productId).map(GoodsReceiptNoteLine::getId).toList();
        return lots.findByGoodsReceiptNoteLineIdIn(lineIds).stream().map(Lot::getId).findFirst()
                .orElseThrow(() -> new IllegalStateException("no lot for part " + productId + " on GRN " + receiptId));
    }

    private PurchaseOrder orderOf(long receiptId) {
        GoodsReceiptNote receipt = receipts.findById(receiptId).orElseThrow();
        return orders.findById(receipt.getPurchaseOrderId()).orElseThrow();
    }

    /** Owner, or a Branch Manager of the store that received the goods (MSG05 otherwise). */
    private static void requireHandler(AuthenticatedUser user, PurchaseOrder order) {
        boolean manager = user.role() == Role.BRANCH_MANAGER && Objects.equals(user.storeId(), order.getStoreId());
        if (user.role() != Role.OWNER && !manager) {
            throw forbidden();
        }
    }

    private List<DiscrepancyCaseResponse> responses(AuthenticatedUser user, List<DiscrepancyCase> cases,
                                                    boolean withEvents) {
        if (cases.isEmpty()) {
            return List.of();
        }
        Set<Long> receiptIds = cases.stream().map(DiscrepancyCase::getGoodsReceiptNoteId).collect(Collectors.toSet());
        Map<Long, GoodsReceiptNote> receiptById = byId(receipts.findAllById(receiptIds), GoodsReceiptNote::getId);
        Map<Long, PurchaseOrder> orderById = byId(orders.findAllById(receiptById.values().stream()
                .map(GoodsReceiptNote::getPurchaseOrderId).collect(Collectors.toSet())), PurchaseOrder::getId);
        Map<Long, Supplier> supplierById = byId(suppliers.findAllById(orderById.values().stream()
                .map(PurchaseOrder::getSupplierId).collect(Collectors.toSet())), Supplier::getId);
        Map<Long, String> storeNames = stores.findAllById(orderById.values().stream().map(PurchaseOrder::getStoreId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Store::getId, Store::getStoreName));
        Map<Long, Product> parts = byId(products.findAllById(cases.stream().map(DiscrepancyCase::getProductId)
                .collect(Collectors.toSet())), Product::getId);
        List<Long> caseIds = cases.stream().map(DiscrepancyCase::getId).toList();
        Map<Long, Progress> progress = settlement.progress(caseIds);
        Set<Long> replacements = ledger.replacementReceiptIds(receiptIds);
        Set<String> excessAwaiting = receiptLines.findByGoodsReceiptNoteIdInAndExcessDecision(receiptIds,
                        ExcessDecision.AWAITING_OWNER).stream()
                .map(line -> line.getGoodsReceiptNoteId() + ":" + line.getProductId()).collect(Collectors.toSet());

        Map<Long, List<DiscrepancyResolutionEvent>> eventsByCase = withEvents
                ? events.findByDiscrepancyCaseIdInOrderByOccurredAtAscIdAsc(caseIds).stream()
                .collect(Collectors.groupingBy(DiscrepancyResolutionEvent::getDiscrepancyCaseId))
                : Map.of();
        Map<Long, List<AttachmentResponse>> evidence = eventsByCase.isEmpty() ? Map.of()
                : attachments.list(OwnerType.DISCREPANCY_RESOLUTION_EVENT, eventsByCase.values().stream()
                .flatMap(List::stream).map(DiscrepancyResolutionEvent::getId).toList());
        Map<Long, AuditLog> pending = pendingRequests(caseIds);
        Map<Long, Product> requestedParts = byId(products.findAllById(pending.values().stream()
                .map(step -> step.getNewData().get("product_id")).filter(Objects::nonNull)
                .map(value -> ((Number) value).longValue()).collect(Collectors.toSet())), Product::getId);
        Set<Long> people = new HashSet<>();
        cases.forEach(found -> people.add(found.getClosedBy()));
        pending.values().forEach(step -> people.add(step.getActorId()));
        eventsByCase.values().forEach(list -> list.forEach(event -> people.add(event.getRecordedBy())));
        people.remove(null);
        // A HashMap, so that the closer of a case still open (a null id) gives a null name.
        Map<Long, String> names = new HashMap<>();
        if (!people.isEmpty()) {
            users.findAllById(people).forEach(person -> names.put(person.getId(), person.getFullName()));
        }
        boolean owner = user.role() == Role.OWNER;

        List<DiscrepancyCaseResponse> rows = new ArrayList<>();
        for (DiscrepancyCase found : cases) {
            GoodsReceiptNote receipt = receiptById.get(found.getGoodsReceiptNoteId());
            PurchaseOrder order = orderById.get(receipt.getPurchaseOrderId());
            Supplier supplier = supplierById.get(order.getSupplierId());
            Product part = parts.get(found.getProductId());
            Standing standing = new Standing(found, progress.get(found.getId()));
            Progress done = standing.progress();
            boolean manager = user.role() == Role.BRANCH_MANAGER
                    && Objects.equals(user.storeId(), order.getStoreId());
            boolean open = found.isOpen();
            List<Event> history = eventsByCase.getOrDefault(found.getId(), List.of()).stream()
                    .map(event -> new Event(event.getId(), event.getResolutionType(), event.getQuantity(),
                            event.getGoodsReceiptNoteId(), event.getReason(), event.getRecordedBy(),
                            names.get(event.getRecordedBy()), event.getOccurredAt(), event.isConfirmed(),
                            event.getConfirmedAt(), evidence.getOrDefault(event.getId(), List.of())))
                    .toList();
            rows.add(new DiscrepancyCaseResponse(found.getId(), receipt.getId(), replacements.contains(receipt.getId()),
                    order.getId(), supplier.getId(), supplier.getSupplierName(), order.getStoreId(),
                    storeNames.get(order.getStoreId()), part.getId(), part.getSku(), part.getPartName(),
                    part.getBaseUnit(), found.getShortQty(), found.getWrongItemQty(), found.getDamagedQty(),
                    found.getRejectedExcessQty(), found.getReturnRequiredQty(), found.getReplacementRequiredQty(),
                    found.replacementLimit(),
                    done.returned(), done.writtenOff(), done.replaced(), done.waived(), done.pendingReplacement(),
                    standing.outstandingReturn(), standing.outstandingReplacement(), standing.openReplacement(),
                    standing.heldUndecided(), excessAwaiting.contains(receipt.getId() + ":" + found.getProductId()),
                    found.getStatus(), found.getOpenedAt(), found.getClosureType(), found.getClosureReason(),
                    found.getClosedBy(), names.get(found.getClosedBy()), found.getClosedAt(),
                    open && (owner || manager) && (found.heldQty() > found.getReturnRequiredQty()
                            || found.getReturnRequiredQty() > returnFloor(found, done)
                            || found.replacementLimit() > replacementFloor(found, done)),
                    open && manager && standing.outstandingReturn() > 0,
                    open && owner && standing.openReplacement() > 0,
                    open && manager && standing.openReplacement() > 0, history,
                    standing.damagedUndecided(), standing.wrongUndecided(),
                    open && (owner || manager) && !pending.containsKey(found.getId())
                            && standing.damagedUndecided() + standing.wrongUndecided() > 0,
                    open && owner && standing.damagedUndecided() + standing.wrongUndecided() > 0,
                    open ? pendingRequest(pending.get(found.getId()), requestedParts, names) : null));
        }
        return rows;
    }

    /**
     * The least the agreed replacement may be: the short quantity, which only Owner settles without replacement (UC-46
     * Step 3_Shortage settled without replacement), and what was already replaced, waived or is on its way.
     */
    private static int replacementFloor(DiscrepancyCase found, Progress done) {
        return Math.max(found.getShortQty(), done.replaced() + done.waived() + done.pendingReplacement());
    }

    /** The return and the replacement one line of a handling asks for. */
    private record Agreed(int returnQty, int replacementQty) {
    }

    private static Map<String, Object> obligations(DiscrepancyCase found) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("return_required_qty", found.getReturnRequiredQty());
        values.put("replacement_required_qty", found.getReplacementRequiredQty());
        return values;
    }

    private static Specification<DiscrepancyCase> matching(Long storeId, DiscrepancySearch search) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("sourceType"), SourceType.SUPPLIER));
            if (search.status() != null) {
                where.add(cb.equal(root.get("status"), search.status()));
            }
            if (search.goodsReceiptNoteId() != null) {
                where.add(cb.equal(root.get("goodsReceiptNoteId"), search.goodsReceiptNoteId()));
            }
            if (search.purchaseOrderId() != null) {
                Subquery<Long> ofOrder = query.subquery(Long.class);
                Root<GoodsReceiptNote> receipt = ofOrder.from(GoodsReceiptNote.class);
                ofOrder.select(receipt.get("id"))
                        .where(cb.equal(receipt.get("purchaseOrderId"), search.purchaseOrderId()));
                where.add(root.get("goodsReceiptNoteId").in(ofOrder));
            }
            if (search.supplierId() != null || search.deliveredFrom() != null || search.deliveredTo() != null) {
                Subquery<Long> delivered = query.subquery(Long.class);
                Root<GoodsReceiptNote> receipt = delivered.from(GoodsReceiptNote.class);
                List<Predicate> onReceipt = new ArrayList<>();
                if (search.supplierId() != null) {
                    Subquery<Long> ofSupplier = query.subquery(Long.class);
                    Root<PurchaseOrder> order = ofSupplier.from(PurchaseOrder.class);
                    ofSupplier.select(order.get("id")).where(cb.equal(order.get("supplierId"), search.supplierId()));
                    onReceipt.add(receipt.get("purchaseOrderId").in(ofSupplier));
                }
                if (search.deliveredFrom() != null) {
                    onReceipt.add(cb.greaterThanOrEqualTo(receipt.get("arrivedAt"),
                            search.deliveredFrom().atStartOfDay()));
                }
                if (search.deliveredTo() != null) {
                    onReceipt.add(cb.lessThan(receipt.get("arrivedAt"), search.deliveredTo().plusDays(1)
                            .atStartOfDay()));
                }
                delivered.select(receipt.get("id")).where(onReceipt.toArray(Predicate[]::new));
                where.add(root.get("goodsReceiptNoteId").in(delivered));
            }
            if (StringUtils.hasText(search.q())) {
                String like = SearchText.likePattern(search.q());
                Subquery<Long> parts = query.subquery(Long.class);
                Root<Product> part = parts.from(Product.class);
                parts.select(part.get("id")).where(cb.or(SearchText.like(cb, part.get("sku"), like),
                        SearchText.like(cb, part.get("partName"), like)));
                where.add(root.get("productId").in(parts));
            }
            if (storeId != null) {
                Subquery<Long> ordersOfStore = query.subquery(Long.class);
                Root<PurchaseOrder> order = ordersOfStore.from(PurchaseOrder.class);
                ordersOfStore.select(order.get("id")).where(cb.equal(order.get("storeId"), storeId));
                Subquery<Long> ofStore = query.subquery(Long.class);
                Root<GoodsReceiptNote> receipt = ofStore.from(GoodsReceiptNote.class);
                ofStore.select(receipt.get("id")).where(receipt.get("purchaseOrderId").in(ordersOfStore));
                where.add(root.get("goodsReceiptNoteId").in(ofStore));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static String text(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "MSG133");
    }

    private static BusinessException changed() {
        return new BusinessException(HttpStatus.CONFLICT, "MSG29");
    }

    private static BusinessException forbidden() {
        return new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
    }

    private static <T> Map<Long, T> byId(Collection<T> rows, Function<T, Long> id) {
        return rows.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}
