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
import fu.hl.g64.motorflow.discrepancy.dto.DecisionApprovalRequest;
import fu.hl.g64.motorflow.discrepancy.dto.DecisionRejectionRequest;
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
import fu.hl.g64.motorflow.discrepancy.entity.enums.DecisionStatus;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DecisionType;
import fu.hl.g64.motorflow.discrepancy.entity.enums.GoodsKind;
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
import java.util.TreeMap;
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
    private static final String GRN_REF_TYPE = "GOODS_RECEIPT_NOTE";
    /** GVHD 24/11: audit_log actions of a direction proposed to, approved or sent back by the Owner. */
    private static final String DECISION_PROPOSED = "DISCREPANCY_DECISION_PROPOSED";
    private static final String DECISION_APPROVED = "DISCREPANCY_DECISION_APPROVED";
    private static final String DECISION_REJECTED = "DISCREPANCY_DECISION_REJECTED";

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

    // UC-46 steps 3-7, GVHD 24/11: the direction agreed with the supplier for each case, decided or proposed
    @Transactional
    @Override
    public List<DiscrepancyCaseResponse> saveHandling(AuthenticatedUser handler, long receiptId,
                                                      DiscrepancyHandlingRequest request) {
        GoodsReceiptNote receipt = supplierReceipt(receiptId);
        PurchaseOrder order = orders.findById(receipt.getPurchaseOrderId()).orElseThrow();
        requireHandler(handler, order);
        boolean owner = handler.role() == Role.OWNER;
        // Lock order: cases, then stock.
        List<DiscrepancyCase> cases = discrepancies.findByReceiptForUpdate(receiptId);
        Map<Long, DiscrepancyCase> caseById = byId(cases, DiscrepancyCase::getId);
        Map<Long, Standing> standings = settlement.standings(cases);
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
            Standing standing = standings.get(found.getId());
            Progress done = standing.progress();
            DecisionType type = line.direction();
            int wrong;
            int damaged;
            if (type == DecisionType.ACCEPT_NO_REPLACEMENT) {
                // (3) The goods are kept: nothing of them goes back (the rejected excess always does).
                wrong = line.wrongReturnQty() == null ? 0 : line.wrongReturnQty();
                damaged = line.damagedReturnQty() == null ? 0 : line.damagedReturnQty();
                if (wrong + damaged > 0 || line.returnRequiredQty() != null
                        && line.returnRequiredQty() > found.getRejectedExcessQty()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG354", n);
                }
            } else if (line.wrongReturnQty() == null && line.damagedReturnQty() == null) {
                // The older single figure: rejected excess first, then the wrong items, then the damaged goods.
                int whole = line.returnRequiredQty();
                // The rejected excess always goes back; what went back already is checked by kind below (after MSG355).
                if (whole < found.getRejectedExcessQty()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG134", n, returnFloor(found, done));
                }
                int moved = whole - found.getRejectedExcessQty();
                wrong = Math.min(moved, found.getWrongItemQty());
                damaged = moved - wrong;
            } else {
                wrong = line.wrongReturnQty() != null ? line.wrongReturnQty() : found.getWrongReturnRequiredQty();
                damaged = line.damagedReturnQty() != null ? line.damagedReturnQty()
                        : found.getDamagedReturnRequiredQty();
            }
            // GVHD 24/11: (2) returns what is agreed and the supplier replaces; (1) and (3) take no replacement.
            Integer asked = line.replacementRequiredQty();
            int standard = standardReplacement(found, wrong, damaged);
            int replacement;
            if (type == DecisionType.RETURN_AND_REPLACE) {
                replacement = asked != null ? asked : standard;
            } else {
                if (asked != null && asked != 0) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG354", n);
                }
                replacement = 0;
            }
            // A line sent back as the case holds it changes nothing and is not checked again (review 25/11).
            if (unchanged(found, type, wrong, damaged, replacement, owner)) {
                wanted.put(found.getId(), new Agreed(type, wrong, damaged, replacement, false, true));
                continue;
            }
            // UC-46 Step 5_Invalid or stale quantities. GVHD 20/11: goods Owner took into stock as another part are no
            // longer held; góp ý UI 21/11: each kind on its own.
            if (wrong > found.getWrongItemQty() - done.acceptedWrong()
                    || damaged > found.getDamagedQty() - done.acceptedDamaged()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG35", n);
            }
            if (type == DecisionType.RETURN_AND_REPLACE) {
                // UC-46 step 3, BR-27: never above the case's limit (BR-67).
                if (replacement > found.replacementLimit()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG35", n);
                }
                // Nothing to return or replace (a shortage keeps its replacement: MSG193 below).
                if (wrong + damaged + replacement == 0 && found.getShortQty() == 0) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG354", n);
                }
            } else if (type == DecisionType.ACCEPT_NO_REPLACEMENT
                    ? found.getWrongItemQty() + found.getDamagedQty() == 0
                    : wrong + damaged + found.getShortQty() == 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG354", n);
            }
            // GVHD 24/11: once goods went back, came in or were kept, or a replacement was waived, the direction holds.
            if (actedOn(found, done)) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG355", n);
            }
            // Test report 20/11: never below what of that kind went back already.
            int wrongFloor = standing.returnedOf(GoodsKind.WRONG_ITEM);
            int damagedFloor = standing.returnedOf(GoodsKind.DAMAGED);
            if (wrong < wrongFloor || damaged < damagedFloor) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG134", n,
                        wrong < wrongFloor ? wrongFloor : damagedFloor);
            }
            // A return recorded without a kind before V21 is read by the agreed wrong items: the new figures must not
            // move it to another kind (the damaged goods already returned stay damaged goods).
            if (standing.returnedOf(GoodsKind.DAMAGED, wrong) != damagedFloor) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG134", n, damagedFloor);
            }
            int floor = replacementFloor(found, done);
            if (type == DecisionType.RETURN_AND_REPLACE && replacement < floor) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG193", n, floor);
            }
            // PM Q2: a Branch Manager decides alone only (2) with the full replacement; the rest waits for the Owner.
            boolean decides = owner || type == DecisionType.RETURN_AND_REPLACE && replacement == standard;
            wanted.put(found.getId(), new Agreed(type, wrong, damaged, replacement, decides, false));
        }

        String note = text(request.note());
        LocalDateTime now = LocalDateTime.now(clock);
        List<DiscrepancyCase> handled = cases.stream().filter(found -> wanted.containsKey(found.getId()))
                .sorted(Comparator.comparing(DiscrepancyCase::getProductId)).toList();
        int proposed = 0;
        for (DiscrepancyCase found : handled) {
            Agreed agreed = wanted.get(found.getId());
            if (agreed.unchanged()) {
                continue;
            }
            Map<String, Object> old = obligations(found);
            if (agreed.decides()) {
                carryOut(found, agreed, receiptId, order.getStoreId(), handler.id(), note, now);
                found.decide(agreed.type(), handler.id(), now);
            } else {
                // Review 25/11: what was decided before stays in force (goods held as agreed) until the Owner answers;
                // an undecided case holds nothing for return until then.
                found.propose(agreed.type(), agreed.wrongQty(), agreed.damagedQty(), agreed.replacementQty(),
                        handler.id(), now);
                proposed++;
            }
            auditLog.save(AuditLog.record(AUDIT_ENTITY, found.getId(), agreed.decides() ? HANDLING_SAVED
                    : DECISION_PROPOSED, handler.id(), note, old, decision(found), now));
        }
        if (proposed > 0) {
            notifications.notifyOwners("DISCREPANCY_DECISION_REQUESTED",
                    "Phiếu nhập #%d (đơn nhập hàng %s): %d dòng sai lệch chờ Owner duyệt hướng xử lý với NCC."
                            .formatted(receiptId, PurchaseOrderCode.of(order.getId()), proposed),
                    GRN_REF_TYPE, receiptId);
        }
        handled.forEach(found -> settlement.closeIfSettled(found, handler.id(), now));
        return responses(handler, cases, true);
    }

    // GVHD 24/11: the Owner approves what Branch Managers proposed
    @Transactional
    @Override
    public List<DiscrepancyCaseResponse> approveDecisions(AuthenticatedUser owner, long receiptId,
                                                          DecisionApprovalRequest request) {
        requireOwner(owner);
        GoodsReceiptNote receipt = supplierReceipt(receiptId);
        PurchaseOrder order = orders.findById(receipt.getPurchaseOrderId()).orElseThrow();
        List<DiscrepancyCase> cases = discrepancies.findByReceiptForUpdate(receiptId);
        List<DiscrepancyCase> chosen = awaitingOwner(cases, request.caseIds());
        Map<Long, Standing> standings = settlement.standings(chosen);
        LocalDateTime now = LocalDateTime.now(clock);
        for (DiscrepancyCase found : chosen) {
            Agreed agreed = new Agreed(found.getDecisionType(), found.getProposedWrongReturnQty(),
                    found.getProposedDamagedReturnQty(), found.getProposedReplacementQty(), true, false);
            Progress done = standings.get(found.getId()).progress();
            if (actedOn(found, done) || agreed.wrongQty() > found.getWrongItemQty() - done.acceptedWrong()
                    || agreed.damagedQty() > found.getDamagedQty() - done.acceptedDamaged()
                    || agreed.replacementQty() > found.replacementLimit()) {
                throw changed();
            }
            Map<String, Object> old = obligations(found);
            found.approve(owner.id(), now);
            carryOut(found, agreed, receiptId, order.getStoreId(), owner.id(), null, now);
            auditLog.save(AuditLog.record(AUDIT_ENTITY, found.getId(), DECISION_APPROVED, owner.id(), null, old,
                    decision(found), now));
        }
        notifications.notifyStoreManagers(order.getStoreId(), "DISCREPANCY_DECISION_APPROVED",
                "Owner đã duyệt hướng xử lý %d dòng sai lệch của phiếu nhập #%d (đơn nhập hàng %s)."
                        .formatted(chosen.size(), receiptId, PurchaseOrderCode.of(order.getId())),
                GRN_REF_TYPE, receiptId);
        chosen.forEach(found -> settlement.closeIfSettled(found, owner.id(), now));
        return responses(owner, cases, true);
    }

    // GVHD 24/11: the Owner sends proposals back
    @Transactional
    @Override
    public List<DiscrepancyCaseResponse> rejectDecisions(AuthenticatedUser owner, long receiptId,
                                                         DecisionRejectionRequest request) {
        requireOwner(owner);
        if (!StringUtils.hasText(request.reason())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG356");
        }
        GoodsReceiptNote receipt = supplierReceipt(receiptId);
        PurchaseOrder order = orders.findById(receipt.getPurchaseOrderId()).orElseThrow();
        List<DiscrepancyCase> cases = discrepancies.findByReceiptForUpdate(receiptId);
        List<DiscrepancyCase> chosen = awaitingOwner(cases, request.caseIds());
        String reason = request.reason().strip();
        LocalDateTime now = LocalDateTime.now(clock);
        for (DiscrepancyCase found : chosen) {
            Map<String, Object> old = decision(found);
            found.rejectProposal();
            auditLog.save(AuditLog.record(AUDIT_ENTITY, found.getId(), DECISION_REJECTED, owner.id(), reason, old,
                    decision(found), now));
        }
        notifications.notifyStoreManagers(order.getStoreId(), "DISCREPANCY_DECISION_REJECTED",
                "Owner không duyệt hướng xử lý %d dòng sai lệch của phiếu nhập #%d: %s. Vui lòng chọn lại hướng xử lý."
                        .formatted(chosen.size(), receiptId, reason), GRN_REF_TYPE, receiptId);
        return responses(owner, cases, true);
    }

    /** A confirmed supplier delivery (MSG132 otherwise). */
    private GoodsReceiptNote supplierReceipt(long receiptId) {
        return receipts.findById(receiptId).filter(r -> r.getReceiptType() == ReceiptType.SUPPLIER_DELIVERY)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG132"));
    }

    /** The cases awaiting the Owner among {@code caseIds} (all of them when empty), by part; MSG360 if none. */
    private static List<DiscrepancyCase> awaitingOwner(List<DiscrepancyCase> cases, List<Long> caseIds) {
        boolean all = caseIds == null || caseIds.isEmpty();
        List<DiscrepancyCase> chosen = cases.stream()
                .filter(found -> found.getDecisionStatus() == DecisionStatus.AWAITING_OWNER)
                .filter(found -> all || caseIds.contains(found.getId()))
                .sorted(Comparator.comparing(DiscrepancyCase::getProductId)).toList();
        if (chosen.isEmpty() || !all && chosen.size() != new HashSet<>(caseIds).size()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG360");
        }
        return chosen;
    }

    /**
     * Carries a decided direction out: the wrong items and damaged goods to return move to awaiting return to
     * supplier (or back from it), and the case agrees them. (1) and (3) keep the least replacement the case allows
     * and waive what of it no delivery is bringing, as the Owner's waiver does (UC-46 Step 3).
     */
    private void carryOut(DiscrepancyCase found, Agreed agreed, long receiptId, long storeId, long by, String note,
                          LocalDateTime now) {
        int wrongMove = agreed.wrongQty() - found.getWrongReturnRequiredQty();
        int damagedMove = agreed.damagedQty() - found.getDamagedReturnRequiredQty();
        if (wrongMove != 0 || damagedMove != 0) {
            // Rejected excess is already awaiting return (UC-45); góp ý UI 21/11: each kind moves as agreed for it.
            long lotId = lotOf(receiptId, found.getProductId());
            hold(found, StockStatus.WRONG_ITEM, wrongMove, lotId, storeId, by, note, now);
            hold(found, StockStatus.DAMAGED, damagedMove, lotId, storeId, by, note, now);
        }
        boolean replaced = agreed.type() == DecisionType.RETURN_AND_REPLACE;
        found.agree(agreed.wrongQty(), agreed.damagedQty(), replaced ? agreed.replacementQty()
                : replacementFloor(found, settlement.standing(found).progress()));
        if (!replaced) {
            int open = settlement.standing(found).openReplacement();
            if (open > 0) {
                events.save(DiscrepancyResolutionEvent.done(found.getId(), ResolutionType.CLOSED_BY_AGREEMENT, open,
                        note != null ? note : agreed.type() == DecisionType.ACCEPT_NO_REPLACEMENT
                                ? "Nhận hàng lỗi, không cần giao bù" : "Trả hàng, không cần giao bù", by, now));
            }
        }
    }

    /** PM Q2: the replacement a Branch Manager may agree alone: everything short and sent back, within the limit. */
    private static int standardReplacement(DiscrepancyCase found, int wrong, int damaged) {
        return Math.min(found.getShortQty() + wrong + damaged, found.replacementLimit());
    }

    /** Whether a line repeats what the case holds already, so that saving the whole table again changes nothing. */
    private static boolean unchanged(DiscrepancyCase found, DecisionType type, int wrong, int damaged,
                                     int replacement, boolean owner) {
        if (found.getDecisionType() != type) {
            return false;
        }
        return switch (found.getDecisionStatus()) {
            case DECIDED -> wrong == found.getWrongReturnRequiredQty() && damaged == found.getDamagedReturnRequiredQty()
                    && (type != DecisionType.RETURN_AND_REPLACE || replacement == found.getReplacementRequiredQty());
            // The Owner saving a proposal as it is decides it.
            case AWAITING_OWNER -> !owner && wrong == found.getProposedWrongReturnQty()
                    && damaged == found.getProposedDamagedReturnQty()
                    && replacement == found.getProposedReplacementQty();
            case UNDECIDED -> false;
        };
    }

    /**
     * GVHD 24/11: something was done on the case that fixes its direction: goods returned (the rejected excess aside),
     * replaced or on their way, written off or taken into stock, or a replacement waived. Review 25/11 (R-09): goods
     * the Owner took into stock before anything was decided leave the rest to decide, so they do not lock the case.
     */
    private static boolean actedOn(DiscrepancyCase found, Progress done) {
        boolean kept = found.isDecided() && (done.acceptedDamaged() > 0 || done.acceptedWrong() > 0);
        return done.returned() > done.returnedExcess() || done.writtenOff() > 0 || done.replaced() > 0
                || done.pendingReplacement() > 0 || done.waived() > 0 || kept;
    }

    /** The case's obligations with its direction, as audit_log keeps them. */
    private static Map<String, Object> decision(DiscrepancyCase found) {
        Map<String, Object> values = obligations(found);
        values.put("decision_type", found.getDecisionType() == null ? null : found.getDecisionType().name());
        values.put("decision_status", found.getDecisionStatus().name());
        values.put("proposed_wrong_return_qty", found.getProposedWrongReturnQty());
        values.put("proposed_damaged_return_qty", found.getProposedDamagedReturnQty());
        values.put("proposed_replacement_qty", found.getProposedReplacementQty());
        return values;
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
        Standing standing = settlement.standing(found);
        GoodsKind kind = request.goodsKind();
        // GVHD 24/11: wrong items and damaged goods go back once the direction is decided; the excess Owner rejected
        // always goes back.
        // Without a kind, no more than the excess may go back then (review 25/11: the wrong items or damaged goods of
        // a direction under review stay until the Owner answers).
        if (!found.isDecided() && (kind != null && kind != GoodsKind.REJECTED_EXCESS
                || standing.outstandingReturn(GoodsKind.REJECTED_EXCESS) == 0
                || kind == null && request.quantity() > standing.outstandingReturn(GoodsKind.REJECTED_EXCESS))) {
            throw undecided(found, "MSG357");
        }
        if (standing.outstandingReturn() == 0) {
            throw changed();
        }
        // Góp ý UI 21/11: a return of one kind. Without a kind it is taken as the rejected excess first, then the wrong
        // items, then the damaged goods, and that reading is stored with the return, so a later change of the agreed
        // return per kind does not move what already went back to another kind.
        int left = kind != null ? standing.outstandingReturn(kind) : standing.outstandingReturn();
        if (request.quantity() > left) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG135", left);
        }
        Map<GoodsKind, Integer> parts = new LinkedHashMap<>();
        if (kind != null) {
            parts.put(kind, request.quantity());
        } else {
            int rest = request.quantity();
            for (GoodsKind each : List.of(GoodsKind.REJECTED_EXCESS, GoodsKind.WRONG_ITEM, GoodsKind.DAMAGED)) {
                int part = Math.min(rest, standing.outstandingReturn(each));
                if (part > 0) {
                    parts.put(each, part);
                    rest -= part;
                }
            }
        }
        String note = text(request.note());
        LocalDateTime now = LocalDateTime.now(clock);
        long lotId = lotOf(receiptId, found.getProductId());
        boolean first = true;
        for (Map.Entry<GoodsKind, Integer> part : parts.entrySet()) {
            int quantity = part.getValue();
            DiscrepancyResolutionEvent event = events.save(DiscrepancyResolutionEvent.returned(id, part.getKey(),
                    quantity, note, manager.id(), now));
            // The goods leave the store from awaiting return to supplier, valued at the average (BR-74).
            posting.takeOut(new OutPosting(order.getStoreId(), found.getProductId(),
                    StockStatus.AWAITING_SUPPLIER_RETURN, quantity, List.of(new LotQuantity(lotId, quantity)),
                    MovementType.SUPPLIER_RETURN, SourceRef.discrepancyResolutionEvent(event.getId()), manager.id(),
                    note));
            // The evidence of the hand-over goes with its first part.
            attachments.attach(OwnerType.DISCREPANCY_RESOLUTION_EVENT, event.getId(),
                    !first || request.evidence() == null ? List.of() : request.evidence(), manager.id());
            first = false;
        }
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
        if (found.getDecisionStatus() == DecisionStatus.AWAITING_OWNER) {
            throw undecided(found, "MSG357");
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
        // GVHD 24/11: the Owner waiving on an undecided shortage decides it: return, no replacement. With wrong or
        // damaged goods the direction must be decided first, as what goes back is not agreed yet (review 25/11).
        if (!found.isDecided() && found.getWrongItemQty() + found.getDamagedQty() > 0) {
            throw undecided(found, "MSG357");
        }
        if (!found.isDecided()) {
            found.decide(DecisionType.RETURN_NO_REPLACEMENT, owner.id(), now);
        }
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
        if (found.getDecisionStatus() == DecisionStatus.AWAITING_OWNER) {
            throw undecided(found, "MSG357");
        }
        Standing standing = settlement.standing(found);
        boolean damaged = request.type() == AcceptanceRequest.Type.DAMAGED;
        if (pendingRequests(List.of(id)).getOrDefault(id, Map.of()).containsKey(request.type().name())) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG314", kindName(request.type().name()));
        }
        int undecided = damaged ? standing.damagedUndecided() : standing.wrongUndecided();
        if (request.quantity() > undecided) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG309", undecided);
        }
        Product received = null;
        if (!damaged) {
            // Góp ý UI 21/11: goods delivered by mistake are taken in as exactly one part, which must be chosen.
            if (request.productId() == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG334");
            }
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
        String what = damaged ? "%d %s bị lỗi làm hàng lỗi".formatted(request.quantity(), part.getSku())
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
        if (!found.isOpen()) {
            throw changed();
        }
        String type = request.type().name();
        if (!pendingRequests(List.of(id)).getOrDefault(id, Map.of()).containsKey(type)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG315", kindName(type));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String reason = request.reason().strip();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        data.put("reason", reason);
        auditLog.save(AuditLog.record(AUDIT_ENTITY, id, ACCEPTANCE_REJECTED, owner.id(), reason, null, data, now));
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
        // GVHD 24/11: not over a proposal the Owner has not answered.
        if (found.getDecisionStatus() == DecisionStatus.AWAITING_OWNER) {
            throw undecided(found, "MSG357");
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
                    now.toLocalDate(), "Giá hàng lỗi, sai lệch #" + id, false));
        } else if (pricingPolicies.findRetailPricesInEffect(List.of(variant.getId()), now).isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG311", original.getSku());
        }
        long lotId = lotOf(receiptId, found.getProductId());
        Lot lot = lots.findById(lotId).orElseThrow();
        String note = "Nhận hàng lỗi thành " + variant.getSku();
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
        keptByOwner(found, owner, now);
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
        // GVHD 24/11: not over a proposal the Owner has not answered.
        if (found.getDecisionStatus() == DecisionStatus.AWAITING_OWNER) {
            throw undecided(found, "MSG357");
        }
        int undecided = settlement.standing(found).wrongUndecided();
        if (request.quantity() > undecided) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG309", undecided);
        }
        Product received = receivedPart(found, request.productId());
        LocalDateTime now = LocalDateTime.now(clock);
        // As goods receipt does for a line (MSG137); goods already past their expiry date do not come into stock.
        if (request.manufactureDate() != null && request.expiryDate() != null
                && request.expiryDate().isBefore(request.manufactureDate())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG137", 1);
        }
        if (request.expiryDate() != null && request.expiryDate().isBefore(now.toLocalDate())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG318");
        }
        long lotId = lotOf(receiptId, found.getProductId());
        String note = "Nhận hàng giao sai thành " + received.getSku();
        DiscrepancyResolutionEvent event = events.save(DiscrepancyResolutionEvent.done(id,
                ResolutionType.RECEIVED_AS_OTHER_PRODUCT, request.quantity(), note, owner.id(), now));
        SourceRef source = SourceRef.discrepancyResolutionEvent(event.getId());
        posting.takeOut(new OutPosting(order.getStoreId(), found.getProductId(), StockStatus.WRONG_ITEM,
                request.quantity(), List.of(new LotQuantity(lotId, request.quantity())),
                MovementType.WRONG_ITEM_RECLASSIFIED, source, owner.id(), note));
        posting.receiveNewLot(new NewLotPosting(order.getStoreId(), received.getId(), LotSourceType.DISCREPANCY, null,
                order.getSupplierId(), now.toLocalDate(), request.unitCost(), text(request.manufacturerLotNo()),
                request.manufactureDate(), request.expiryDate(),
                Map.of(StockStatus.AVAILABLE, request.quantity()), MovementType.WRONG_ITEM_RECLASSIFIED, source,
                owner.id(), note));
        if (!request.replacementNeeded()) {
            // As the Owner's waiver: the replacement still owed for these units is not to come.
            int waived = Math.min(request.quantity(), waivableForWrongItems(settlement.standing(found)));
            if (waived > 0) {
                events.save(DiscrepancyResolutionEvent.done(id, ResolutionType.CLOSED_BY_AGREEMENT, waived,
                        "Đã nhận hàng giao sai, không cần giao bù", owner.id(), now));
            }
        }
        approved(owner, found, "WRONG_ITEM", received.getId(), request.quantity(), now);
        keptByOwner(found, owner, now);
        settlement.closeIfSettled(found, owner.id(), now);
        return responses(owner, List.of(found), true).getFirst();
    }

    /**
     * The replacement Owner may waive together with receiving wrong items: the replacement no delivery is bringing yet
     * ({@code openReplacement}) less what is still owed for the short units, which a wrong item never settles.
     * Replacements and waivers already made are attributed to the short units first, so
     * {@code shortStillOwed = max(0, shortQty - replaced - waived - pendingReplacement)} and the answer is
     * {@code max(0, openReplacement - shortStillOwed)}; the caller caps it by the quantity received.
     */
    private static int waivableForWrongItems(Standing standing) {
        Progress done = standing.progress();
        int shortStillOwed = Math.max(standing.found().getShortQty()
                - done.replaced() - done.waived() - done.pendingReplacement(), 0);
        return Math.max(standing.openReplacement() - shortStillOwed, 0);
    }

    /**
     * The part goods delivered by mistake really are: on sale (MSG313), not the part ordered (MSG310) and not a
     * damaged-goods variant (MSG317).
     */
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
        if (received.getVariantOfProductId() != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG317", received.getSku());
        }
        return received;
    }

    /**
     * The dented-goods variant of {@code original}, made the first time (BR GVHD 20/11): SKU "<SKU>-M" (MSG312 when
     * another part has it), name "<name> (hàng lỗi)", the original's details, its OEM codes and vehicles, no barcode
     * so that a scan never finds it instead of the original.
     */
    private Product variantOf(Product original, long by, LocalDateTime now) {
        if (original.getVariantOfProductId() != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG317", original.getSku());
        }
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
                "Hàng lỗi của " + original.getSku(), now));
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

    /**
     * Plan B4: the Owner taking goods of an undecided case into stock decides it as (3), but only once every wrong
     * or damaged unit held was taken in and no replacement is still owed (review 25/11, R-09). A partial acceptance
     * leaves the case undecided so the Branch Manager still chooses the direction for the rest.
     */
    private void keptByOwner(DiscrepancyCase found, AuthenticatedUser owner, LocalDateTime now) {
        if (found.isDecided()) {
            return;
        }
        // Review 25/11 (R-09, PM): only once every wrong or damaged unit held was taken in and no replacement is owed;
        // otherwise the rest stays undecided for the Branch Manager to choose its direction.
        Standing standing = settlement.standing(found);
        if (standing.heldUndecided() == 0 && standing.openReplacement() == 0) {
            found.decide(DecisionType.ACCEPT_NO_REPLACEMENT, owner.id(), now);
        }
    }

    /** MSG359 while the Owner has a proposal to answer, {@code code} while nothing is decided. */
    private static BusinessException undecided(DiscrepancyCase found, String code) {
        return found.getDecisionStatus() == DecisionStatus.AWAITING_OWNER
                ? new BusinessException(HttpStatus.CONFLICT, "MSG359", "#" + found.getId())
                : new BusinessException(HttpStatus.CONFLICT, code, "#" + found.getId());
    }

    /** What a kind of acceptance request is called in MSG314 and MSG315. */
    private static String kindName(String type) {
        return AcceptanceRequest.Type.DAMAGED.name().equals(type) ? "hàng lỗi" : "hàng giao sai";
    }

    private static DiscrepancyCaseResponse.PendingRequest pendingRequest(AuditLog step, Map<Long, Product> parts,
                                                                         Map<Long, String> names) {
        Map<String, Object> data = step.getNewData();
        Long productId = data.get("product_id") == null ? null : ((Number) data.get("product_id")).longValue();
        Product part = productId == null ? null : parts.get(productId);
        return new DiscrepancyCaseResponse.PendingRequest((String) data.get("type"), productId,
                part == null ? null : part.getSku(), part == null ? null : part.getPartName(),
                ((Number) data.get("quantity")).intValue(), (String) data.get("note"), step.getActorId(),
                names.get(step.getActorId()), step.getOccurredAt());
    }

    /**
     * [case id → [type → its request]] of the requests waiting for Owner: a request is pending per case and type
     * (DAMAGED, WRONG_ITEM) while the latest acceptance step of that type is the request itself. Types are in order,
     * DAMAGED then WRONG_ITEM.
     */
    private Map<Long, Map<String, AuditLog>> pendingRequests(Collection<Long> caseIds) {
        Map<Long, Map<String, AuditLog>> latest = new HashMap<>();
        auditLog.findByEntityTypeAndEntityIdInAndActionIn(AUDIT_ENTITY, caseIds, ACCEPTANCE_ACTIONS).forEach(step -> {
            Object type = step.getNewData() == null ? null : step.getNewData().get("type");
            if (type != null) {
                latest.computeIfAbsent(step.getEntityId(), key -> new TreeMap<>()).merge(type.toString(), step,
                        (a, b) -> Comparator.comparing(AuditLog::getOccurredAt).thenComparing(AuditLog::getId)
                                .compare(a, b) >= 0 ? a : b);
            }
        });
        latest.values().forEach(byType -> byType.values()
                .removeIf(step -> !ACCEPTANCE_REQUESTED.equals(step.getAction())));
        latest.values().removeIf(Map::isEmpty);
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
        Map<Long, Map<String, AuditLog>> pending = pendingRequests(caseIds);
        List<AuditLog> pendingSteps = pending.values().stream().flatMap(byType -> byType.values().stream()).toList();
        Map<Long, Product> requestedParts = byId(products.findAllById(pendingSteps.stream()
                .map(step -> step.getNewData().get("product_id")).filter(Objects::nonNull)
                .map(value -> ((Number) value).longValue()).collect(Collectors.toSet())), Product::getId);
        Set<Long> people = new HashSet<>();
        cases.forEach(found -> {
            people.add(found.getClosedBy());
            people.add(found.getDecidedBy());
            people.add(found.getApprovedBy());
        });
        pendingSteps.forEach(step -> people.add(step.getActorId()));
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
            boolean locked = actedOn(found, done);
            boolean awaitingOwner = found.getDecisionStatus() == DecisionStatus.AWAITING_OWNER;
            Map<String, AuditLog> asked = pending.getOrDefault(found.getId(), Map.of());
            List<Event> history = eventsByCase.getOrDefault(found.getId(), List.of()).stream()
                    .map(event -> new Event(event.getId(), event.getResolutionType(), event.getQuantity(),
                            event.getGoodsReceiptNoteId(), event.getReason(), event.getRecordedBy(),
                            names.get(event.getRecordedBy()), event.getOccurredAt(), event.isConfirmed(),
                            event.getConfirmedAt(), evidence.getOrDefault(event.getId(), List.of()),
                            event.getGoodsKind()))
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
                    // GVHD 24/11: the direction may change until something was done on the case.
                    open && (owner || manager) && !locked,
                    open && manager && standing.outstandingReturn() > 0,
                    open && owner && !awaitingOwner && standing.openReplacement() > 0,
                    open && manager && found.isDecided() && standing.openReplacement() > 0, history,
                    standing.damagedUndecided(), standing.wrongUndecided(),
                    open && (owner || manager)
                            && (standing.damagedUndecided() > 0 && !asked.containsKey("DAMAGED")
                            || standing.wrongUndecided() > 0 && !asked.containsKey("WRONG_ITEM")),
                    open && owner && !awaitingOwner && standing.damagedUndecided() + standing.wrongUndecided() > 0,
                    open ? asked.values().stream().map(step -> pendingRequest(step, requestedParts, names)).toList()
                            : List.of(),
                    found.getWrongReturnRequiredQty(), found.getDamagedReturnRequiredQty(),
                    standing.outstandingReturn(GoodsKind.REJECTED_EXCESS),
                    standing.outstandingReturn(GoodsKind.WRONG_ITEM), standing.outstandingReturn(GoodsKind.DAMAGED),
                    found.getDecisionType(), found.getDecisionStatus(), found.getProposedWrongReturnQty(),
                    found.getProposedDamagedReturnQty(), found.getProposedReplacementQty(), awaitingOwner,
                    found.getDecidedBy(), names.get(found.getDecidedBy()), found.getDecidedAt(),
                    found.getApprovedBy(), names.get(found.getApprovedBy()), found.getApprovedAt(),
                    locked, open && owner && awaitingOwner));
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

    /** The wrong items and damaged goods to return and the replacement one line of a handling asks for. */
    private record Agreed(DecisionType type, int wrongQty, int damagedQty, int replacementQty, boolean decides,
                          boolean unchanged) {
    }

    private static Map<String, Object> obligations(DiscrepancyCase found) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("return_required_qty", found.getReturnRequiredQty());
        values.put("wrong_return_required_qty", found.getWrongReturnRequiredQty());
        values.put("damaged_return_required_qty", found.getDamagedReturnRequiredQty());
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
            if (search.decisionStatus() != null) {
                where.add(cb.equal(root.get("decisionStatus"), search.decisionStatus()));
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
