package fu.hl.g64.motorflow.inventory.helper;

import fu.hl.g64.motorflow.inventory.entity.InventoryPeriod;
import fu.hl.g64.motorflow.inventory.entity.PeriodStockBalance;
import fu.hl.g64.motorflow.inventory.entity.enums.InventoryPeriodStatus;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.repository.InventoryPeriodRepository;
import fu.hl.g64.motorflow.inventory.repository.PeriodLedgerQuery;
import fu.hl.g64.motorflow.inventory.repository.PeriodLedgerQuery.Row;
import fu.hl.g64.motorflow.inventory.repository.PeriodStockBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Opening, in, out and closing quantity and value of each part in each warehouse for one period, worked out from the
 * stock ledger (BR-85). Closing = opening + in − out holds for quantity and value alike, because every figure is a sum
 * of the same movements. Goods in transit belong to the destination from dispatch and are also shown on their own.
 */
@Component
@RequiredArgsConstructor
public class PeriodTotals {

    private static final LocalDate BEFORE_ANY_PERIOD = LocalDate.of(1900, 1, 1);

    private final InventoryPeriodRepository periods;
    private final PeriodLedgerQuery ledger;
    private final PeriodStockBalanceRepository stored;

    /** A quantity and its value at moving weighted average cost. */
    public record Amount(long qty, long value) {
        public static final Amount ZERO = new Amount(0, 0);

        Amount plus(long qty, long value) {
            return new Amount(this.qty + qty, this.value + value);
        }

        Amount minus(Amount other) {
            return new Amount(qty - other.qty, value - other.value);
        }
    }

    /** What one kind of movement brought in and took out. */
    public record Flow(Amount in, Amount out) {
    }

    /** One part in one warehouse; {@code byType} splits in and out by movement type (UC-88). */
    public record Line(long storeId, long productId, Amount opening, Amount in, Amount out, Amount closing,
                       Amount inTransit, Map<MovementType, Flow> byType) {

        /** BR-85: (opening value + value in) / (opening quantity + quantity in); null when that quantity is 0. */
        public BigDecimal periodAvgUnitCost() {
            long qty = opening.qty() + in.qty();
            return qty == 0 ? null : BigDecimal.valueOf(opening.value() + in.value())
                    .divide(BigDecimal.valueOf(qty), 4, RoundingMode.HALF_UP);
        }
    }

    /**
     * Every warehouse and part with an opening balance or a movement in the period, by warehouse then part id.
     * {@code storeIds} null means every warehouse. A closed period gives the balances stored when it was closed
     * (without the split by movement type); an open one is worked out from the ledger now.
     */
    public List<Line> of(InventoryPeriod period, Collection<Long> storeIds) {
        return period.isOpen() ? fromLedger(period, storeIds) : fromStored(period, storeIds);
    }

    /** Worked out from the ledger, whatever the period's status: what closing a period stores (UC-100). */
    public List<Line> fromLedger(InventoryPeriod period, Collection<Long> storeIds) {
        return fromLedger(period.getPeriodMonth(), period.getId(), storeIds);
    }

    /**
     * Worked out from the ledger for the month starting on {@code month}, split by movement type (UC-88). A month
     * with no movement has no period row: it then shows the stock carried in from before, with nothing in or out.
     */
    public List<Line> forMonth(LocalDate month, Collection<Long> storeIds) {
        return periods.findByPeriodMonth(month).map(p -> fromLedger(p, storeIds))
                .orElseGet(() -> fromLedger(month, null, storeIds));
    }

    private List<Line> fromLedger(LocalDate month, Long periodId, Collection<Long> storeIds) {
        InventoryPeriod base = periods.findFirstByStatusAndPeriodMonthBeforeOrderByPeriodMonthDesc(
                InventoryPeriodStatus.CLOSED, month).orElse(null);
        Map<Key, Builder> lines = new TreeMap<>(Comparator.comparingLong(Key::storeId).thenComparingLong(Key::productId));
        for (Row r : ledger.opening(base == null ? null : base.getId(),
                base == null ? BEFORE_ANY_PERIOD : base.getPeriodMonth(), month, storeIds)) {
            line(lines, r).opening = new Amount(r.inQty(), r.inValue());
        }
        if (periodId != null) {
            for (Row r : ledger.flows(periodId, storeIds)) {
                line(lines, r).add(MovementType.valueOf(r.movementType()), r);
            }
        }
        for (Row r : ledger.inTransit(month, storeIds)) {
            line(lines, r).inTransit = new Amount(r.inQty(), r.inValue());
        }
        return lines.entrySet().stream()
                .map(e -> e.getValue().build(e.getKey()))
                .filter(l -> !l.opening().equals(Amount.ZERO) || !l.byType().isEmpty()
                        || !l.inTransit().equals(Amount.ZERO))
                .toList();
    }

    private List<Line> fromStored(InventoryPeriod period, Collection<Long> storeIds) {
        Map<Key, Amount> transit = new TreeMap<>(Comparator.comparingLong(Key::storeId).thenComparingLong(Key::productId));
        for (Row r : ledger.inTransit(period.getPeriodMonth(), storeIds)) {
            transit.put(new Key(r.storeId(), r.productId()), new Amount(r.inQty(), r.inValue()));
        }
        return stored.findByInventoryPeriodId(period.getId()).stream()
                .filter(b -> storeIds == null || storeIds.contains(b.getStoreId()))
                .sorted(Comparator.comparingLong(PeriodStockBalance::getStoreId)
                        .thenComparingLong(PeriodStockBalance::getProductId))
                .map(b -> new Line(b.getStoreId(), b.getProductId(),
                        new Amount(b.getOpeningQty(), b.getOpeningValue()), new Amount(b.getInQty(), b.getInValue()),
                        new Amount(b.getOutQty(), b.getOutValue()), new Amount(b.getClosingQty(), b.getClosingValue()),
                        transit.getOrDefault(new Key(b.getStoreId(), b.getProductId()), Amount.ZERO), Map.of()))
                .toList();
    }

    private static Builder line(Map<Key, Builder> lines, Row r) {
        return lines.computeIfAbsent(new Key(r.storeId(), r.productId()), k -> new Builder());
    }

    private record Key(long storeId, long productId) {
    }

    private static final class Builder {
        private Amount opening = Amount.ZERO;
        private Amount in = Amount.ZERO;
        private Amount out = Amount.ZERO;
        private Amount inTransit = Amount.ZERO;
        private final Map<MovementType, Flow> byType = new EnumMap<>(MovementType.class);

        void add(MovementType type, Row r) {
            if (r.inQty() == 0 && r.inValue() == 0 && r.outQty() == 0 && r.outValue() == 0) {
                return; // only changes of status inside the warehouse
            }
            in = in.plus(r.inQty(), r.inValue());
            out = out.plus(r.outQty(), r.outValue());
            byType.put(type, new Flow(new Amount(r.inQty(), r.inValue()), new Amount(r.outQty(), r.outValue())));
        }

        Line build(Key key) {
            Amount closing = opening.plus(in.qty(), in.value()).minus(out);
            return new Line(key.storeId(), key.productId(), opening, in, out, closing, inTransit,
                    Collections.unmodifiableMap(byType));
        }
    }
}
