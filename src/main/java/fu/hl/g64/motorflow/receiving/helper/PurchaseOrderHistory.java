package fu.hl.g64.motorflow.receiving.helper;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail.DeliveryChange;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail.HistoryEntry;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail.LineChange;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The PO history (UC-42 "changes are kept in history") lives in audit_log: one row per step, with the delivery details
 * and lines before ({@code old_data}) and after ({@code new_data}) a change. The steps of its deliveries and
 * discrepancy cases are added from their own rows (form sync 19/11).
 */
public final class PurchaseOrderHistory {

    public static final String ENTITY = "PURCHASE_ORDER";
    public static final String CREATED = "PURCHASE_ORDER_CREATED";
    public static final String UPDATED = "PURCHASE_ORDER_UPDATED";
    public static final String CANCELLED = "PURCHASE_ORDER_CANCELLED";
    public static final List<String> ACTIONS = List.of(CREATED, UPDATED, CANCELLED);

    private static final String PREFIX = "PURCHASE_ORDER_";

    /** The audit_log steps of a delivery (written by GoodsReceiptServiceImpl) shown in its PO history. */
    public static final String RECEIPT_ENTITY = "GOODS_RECEIPT_NOTE";
    public static final List<String> RECEIPT_ACTIONS = List.of("GOODS_RECEIPT_CREATED", "GOODS_RECEIPT_RECOUNT");

    private PurchaseOrderHistory() {
    }

    /** The agreed quantity and purchase price of one part. */
    public record LineValues(long productId, int quantity, long price) {
    }

    /** What a PO looked like at one moment, as stored in audit_log. */
    public static Map<String, Object> snapshot(LocalDate expectedArrival, String note, List<LineValues> lines) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("expected_arrival", expectedArrival == null ? null : expectedArrival.toString());
        data.put("note", note);
        data.put("lines", lines.stream().map(line -> Map.<String, Object>of("product_id", line.productId(),
                "ordered_qty", line.quantity(), "purchase_price", line.price())).toList());
        return data;
    }

    /** Every part named in the history, so that its SKU and name can be loaded at once. */
    public static Set<Long> productIds(List<AuditLog> steps) {
        Set<Long> ids = new HashSet<>();
        for (AuditLog step : steps) {
            ids.addAll(lines(step.getOldData()).keySet());
            ids.addAll(lines(step.getNewData()).keySet());
        }
        return ids;
    }

    /**
     * One step for the PO detail. Without {@code pricesShown} (a Branch Manager, BR-72) prices are left out, and so is
     * a line whose price alone changed.
     */
    public static HistoryEntry entry(AuditLog step, String actorName, Map<Long, Product> parts, boolean pricesShown) {
        String action = step.getAction().startsWith(PREFIX) ? step.getAction().substring(PREFIX.length())
                : step.getAction();
        if (!UPDATED.equals(step.getAction())) {
            return new HistoryEntry(action, step.getActorId(), actorName, step.getOccurredAt(), step.getReason(), null,
                    List.of(), null, null);
        }
        Map<String, Object> before = step.getOldData() == null ? Map.of() : step.getOldData();
        Map<String, Object> after = step.getNewData() == null ? Map.of() : step.getNewData();

        LocalDate oldArrival = date(before.get("expected_arrival"));
        LocalDate newArrival = date(after.get("expected_arrival"));
        String oldNote = (String) before.get("note");
        String newNote = (String) after.get("note");
        DeliveryChange delivery = Objects.equals(oldArrival, newArrival) && Objects.equals(oldNote, newNote) ? null
                : new DeliveryChange(oldArrival, newArrival, oldNote, newNote);

        Map<Long, LineValues> oldLines = lines(before);
        Map<Long, LineValues> newLines = lines(after);
        List<LineChange> changes = new ArrayList<>();
        oldLines.forEach((productId, old) -> {
            LineValues now = newLines.get(productId);
            if (now == null || (pricesShown ? !old.equals(now) : old.quantity() != now.quantity())) {
                changes.add(change(productId, old, now, parts.get(productId), pricesShown));
            }
        });
        newLines.forEach((productId, now) -> {
            if (!oldLines.containsKey(productId)) {
                changes.add(change(productId, null, now, parts.get(productId), pricesShown));
            }
        });
        return new HistoryEntry(action, step.getActorId(), actorName, step.getOccurredAt(), step.getReason(), delivery,
                changes, null, null);
    }

    /**
     * Form sync 19/11 (item 10): the steps of the PO's deliveries and discrepancy cases. {@code receiptSteps} are the
     * deliveries' audit_log rows ({@link #RECEIPT_ACTIONS}); the other steps come from the deliveries and cases.
     */
    public static List<HistoryEntry> receivingSteps(List<GoodsReceiptNote> deliveries, Set<Long> replacements,
                                                    List<AuditLog> receiptSteps, List<DiscrepancyCase> cases,
                                                    Map<Long, String> names) {
        List<HistoryEntry> steps = new ArrayList<>();
        Map<Long, GoodsReceiptNote> byId = new HashMap<>();
        deliveries.forEach(delivery -> byId.put(delivery.getId(), delivery));
        // A case opens when its delivery is confirmed: listed first, it shows above that step at the same time.
        for (DiscrepancyCase found : cases) {
            steps.add(step("DISCREPANCY_OPENED", openedBy(found, byId.get(found.getGoodsReceiptNoteId())), names,
                    found.getOpenedAt(), null, found.getGoodsReceiptNoteId(), found.getId()));
            if (found.getClosedAt() != null) {
                steps.add(step("DISCREPANCY_CLOSED", found.getClosedBy(), names, found.getClosedAt(),
                        found.getClosureReason(), found.getGoodsReceiptNoteId(), found.getId()));
            }
        }
        for (AuditLog step : receiptSteps) {
            boolean created = "GOODS_RECEIPT_CREATED".equals(step.getAction());
            String action = !created ? "RECEIPT_RECOUNT"
                    : replacements.contains(step.getEntityId()) ? "REPLACEMENT_RECEIPT_CREATED" : "RECEIPT_CREATED";
            steps.add(step(action, step.getActorId(), names, step.getOccurredAt(), step.getReason(),
                    step.getEntityId(), null));
        }
        for (GoodsReceiptNote delivery : deliveries) {
            if (delivery.getSubmittedAt() != null) {
                steps.add(step("RECEIPT_SUBMITTED", delivery.getRecordedBy(), names, delivery.getSubmittedAt(), null,
                        delivery.getId(), null));
            }
            if (delivery.getExcessDecidedAt() != null) {
                steps.add(step("RECEIPT_EXCESS_DECIDED", delivery.getExcessDecidedBy(), names,
                        delivery.getExcessDecidedAt(), null, delivery.getId(), null));
            }
            if (delivery.getConfirmedAt() != null) {
                steps.add(step("RECEIPT_CONFIRMED", delivery.getConfirmedBy(), names, delivery.getConfirmedAt(), null,
                        delivery.getId(), null));
            }
        }
        return steps;
    }

    /**
     * Who opened a case: it has no column of its own, but a case opens in the same transaction, at the same time, as
     * the confirmation of its delivery (Branch Manager) or Owner's rejection of excess. Null if neither matches.
     */
    private static Long openedBy(DiscrepancyCase found, GoodsReceiptNote delivery) {
        if (delivery == null) {
            return null;
        }
        if (found.getOpenedAt().equals(delivery.getConfirmedAt())) {
            return delivery.getConfirmedBy();
        }
        return found.getOpenedAt().equals(delivery.getExcessDecidedAt()) ? delivery.getExcessDecidedBy() : null;
    }

    /** The PO's own steps and its receiving steps together, newest first. */
    public static List<HistoryEntry> newestFirst(List<HistoryEntry> own, List<HistoryEntry> receiving) {
        List<HistoryEntry> all = new ArrayList<>(own);
        all.addAll(receiving);
        all.sort(Comparator.comparing(HistoryEntry::at).reversed());
        return all;
    }

    private static HistoryEntry step(String action, Long actorId, Map<Long, String> names, LocalDateTime at,
                                     String reason, Long receiptId, Long discrepancyId) {
        return new HistoryEntry(action, actorId, actorId == null ? null : names.get(actorId), at, reason, null,
                List.of(), receiptId, discrepancyId);
    }

    private static LineChange change(long productId, LineValues old, LineValues now, Product part,
                                     boolean pricesShown) {
        return new LineChange(productId, part == null ? null : part.getSku(), part == null ? null : part.getPartName(),
                old == null ? null : old.quantity(), now == null ? null : now.quantity(),
                pricesShown && old != null ? old.price() : null, pricesShown && now != null ? now.price() : null);
    }

    /** The lines of a snapshot by part, in the order they were saved. */
    private static Map<Long, LineValues> lines(Map<String, Object> data) {
        Map<Long, LineValues> lines = new LinkedHashMap<>();
        if (data == null || !(data.get("lines") instanceof List<?> rows)) {
            return lines;
        }
        for (Object row : rows) {
            Map<?, ?> line = (Map<?, ?>) row;
            long productId = ((Number) line.get("product_id")).longValue();
            lines.put(productId, new LineValues(productId, ((Number) line.get("ordered_qty")).intValue(),
                    ((Number) line.get("purchase_price")).longValue()));
        }
        return lines;
    }

    private static LocalDate date(Object value) {
        return value == null ? null : LocalDate.parse(value.toString());
    }
}
