package fu.hl.g64.motorflow.transfer.helper;

import fu.hl.g64.motorflow.sales.helper.PickingGuide;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Q7/B2, G3, plan 23/11: the source lots that goods found at the destination but still on the source's books (transfer
 * excess, a wrong item) are taken from. The lots of the expiry the destination recorded; with no expiry recorded,
 * oldest first (FIFO). Only sellable stock counts: what open sales orders hold is never taken.
 */
public final class SourceLots {

    /** {@code blockedCode}: MSG341 when no sellable lot has that expiry, MSG339 when the source cannot cover it. */
    public record Plan(List<PlannedLot> lots, String blockedCode) {
    }

    public record PlannedLot(long lotId, LocalDate expiryDate, int quantity) {
    }

    private SourceLots() {
    }

    public static Plan plan(int quantity, LocalDate expiryDate, PickingGuide.Part available) {
        if (available == null || available.sellableQty() < quantity) {
            return new Plan(List.of(), "MSG339");
        }
        if (expiryDate != null && available.lotsInOrder().stream()
                .noneMatch(lot -> expiryDate.equals(lot.getExpiryDate()))) {
            return new Plan(List.of(), "MSG341");
        }
        List<PlannedLot> selected = new ArrayList<>();
        int remaining = quantity;
        for (int i = 0; i < available.lotsInOrder().size() && remaining > 0; i++) {
            var lot = available.lotsInOrder().get(i);
            if (expiryDate != null && !Objects.equals(lot.getExpiryDate(), expiryDate)) {
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

    /** What is left of {@code available} once a plan took its lots, so the next plan never takes the same units. */
    public static PickingGuide.Part less(PickingGuide.Part available, Plan plan) {
        if (available == null || plan.lots().isEmpty()) {
            return available;
        }
        List<Integer> left = new ArrayList<>(available.lotQuantities());
        int taken = 0;
        for (PlannedLot lot : plan.lots()) {
            for (int i = 0; i < available.lotsInOrder().size(); i++) {
                if (available.lotsInOrder().get(i).getId() == lot.lotId()) {
                    left.set(i, left.get(i) - lot.quantity());
                    taken += lot.quantity();
                }
            }
        }
        return new PickingGuide.Part(available.sellableQty() - taken, available.heldByOthersQty(),
                available.storageArea(), available.binLocation(), available.barcodes(), available.lotsInOrder(), left);
    }
}
