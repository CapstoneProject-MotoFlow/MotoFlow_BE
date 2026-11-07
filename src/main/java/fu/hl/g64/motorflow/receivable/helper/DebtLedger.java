package fu.hl.g64.motorflow.receivable.helper;

import fu.hl.g64.motorflow.receivable.entity.Receivable;
import fu.hl.g64.motorflow.receivable.entity.enums.OriginType;
import fu.hl.g64.motorflow.receivable.repository.ReceivableRepository;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Customer debts with what is still owed on them (BR-53), and the order in which debt payments settle them (BR-70). */
@Component
@RequiredArgsConstructor
public class DebtLedger {

    private final ReceivableRepository receivables;
    private final SalesOrderRepository salesOrders;
    private final Clock clock;

    /** A receivable and what is still owed on it in VND; a correction never makes it less than 0. */
    public record Debt(Receivable receivable, long remaining) {

        public long id() {
            return receivable.getId();
        }

        /** BR-53: still owed after its due date. It is only highlighted, never blocks a sale. */
        public boolean overdue(LocalDate today) {
            return remaining > 0 && receivable.getDueDate() != null && receivable.getDueDate().isBefore(today);
        }
    }

    /** The VND of one payment that goes to one debt. */
    public record Allocation(Debt debt, long amount) {
    }

    /** Today in Vietnam; a debt due today is not overdue yet. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Every receivable of the customer, settled ones included, at one store or every store, in settlement order. */
    public List<Debt> debtsOf(long customerId, Long storeId) {
        return inSettlementOrder(debts(receivables.findWithRemaining(customerId, storeId)));
    }

    /** Receivables still owed at one store, or at every store when {@code storeId} is null, in settlement order. */
    public List<Debt> owedAt(Long storeId) {
        return inSettlementOrder(debts(receivables.findUnsettledWithRemainingAt(storeId)).stream()
                .filter(debt -> debt.remaining() > 0).toList());
    }

    /** What each customer owes the whole chain (BR-53); customers who owe nothing are left out. */
    public Map<Long, Long> owedByCustomer(Collection<Long> customerIds) {
        Map<Long, Long> owed = new HashMap<>();
        if (!customerIds.isEmpty()) {
            debts(receivables.findUnsettledWithRemaining(customerIds)).stream().filter(debt -> debt.remaining() > 0)
                    .forEach(debt -> owed.merge(debt.receivable().getCustomerId(), debt.remaining(), Long::sum));
        }
        return owed;
    }

    /** Applied document corrections of each receivable's amount, in VND; receivables without any are left out. */
    public Map<Long, Long> corrections(Collection<Long> receivableIds) {
        Map<Long, Long> corrections = new HashMap<>();
        if (!receivableIds.isEmpty()) {
            receivables.sumCorrections(receivableIds)
                    .forEach(row -> corrections.put((Long) row[0], ((Number) row[1]).longValue()));
        }
        return corrections;
    }

    /**
     * BR-70: opening debts first, oldest recorded first; then credit sales by the time their order was completed and
     * by order number. Due dates do not change the order.
     */
    public List<Debt> inSettlementOrder(List<Debt> debts) {
        Set<Long> orderIds = debts.stream().map(debt -> debt.receivable().getSalesOrderId()).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, LocalDateTime> completedAt = salesOrders.findAllById(orderIds).stream()
                .filter(order -> order.getCompletedAt() != null)
                .collect(Collectors.toMap(SalesOrder::getId, SalesOrder::getCompletedAt));
        Function<Debt, LocalDateTime> arose = debt -> debt.receivable().getSalesOrderId() == null
                ? debt.receivable().getRecognizedAt()
                : completedAt.getOrDefault(debt.receivable().getSalesOrderId(), debt.receivable().getRecognizedAt());
        Comparator<Debt> order = Comparator
                .comparing((Debt debt) -> debt.receivable().getOriginType() == OriginType.OPENING_BALANCE ? 0 : 1)
                .thenComparing(arose)
                .thenComparing(debt -> debt.receivable().getSalesOrderId(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Debt::id);
        List<Debt> sorted = new ArrayList<>(debts);
        sorted.sort(order);
        return sorted;
    }

    /**
     * BR-70: {@code amount} over debts already in settlement order, each settled in full before the next is reduced.
     * Debts the amount does not reach get no allocation. The caller has checked that the debts can take the amount.
     */
    public static List<Allocation> allocate(List<Debt> orderedDebts, long amount) {
        List<Allocation> allocations = new ArrayList<>();
        long left = amount;
        for (Debt debt : orderedDebts) {
            if (left == 0) {
                break;
            }
            long share = Math.min(left, debt.remaining());
            if (share > 0) {
                allocations.add(new Allocation(debt, share));
                left -= share;
            }
        }
        return allocations;
    }

    private static List<Debt> debts(List<Object[]> rows) {
        return rows.stream()
                .map(row -> new Debt((Receivable) row[0], Math.max(0, ((Number) row[1]).longValue())))
                .toList();
    }
}
