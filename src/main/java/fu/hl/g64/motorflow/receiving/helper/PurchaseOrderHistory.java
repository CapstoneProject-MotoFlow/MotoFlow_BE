package fu.hl.g64.motorflow.receiving.helper;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail.DeliveryChange;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail.HistoryEntry;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail.LineChange;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The PO history (UC-42 "changes are kept in history") lives in audit_log: one row per step, with the delivery details
 * and lines before ({@code old_data}) and after ({@code new_data}) a change.
 */
public final class PurchaseOrderHistory {

    public static final String ENTITY = "PURCHASE_ORDER";
    public static final String CREATED = "PURCHASE_ORDER_CREATED";
    public static final String UPDATED = "PURCHASE_ORDER_UPDATED";
    public static final String CANCELLED = "PURCHASE_ORDER_CANCELLED";
    public static final List<String> ACTIONS = List.of(CREATED, UPDATED, CANCELLED);

    private static final String PREFIX = "PURCHASE_ORDER_";

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
                    List.of());
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
                changes);
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
