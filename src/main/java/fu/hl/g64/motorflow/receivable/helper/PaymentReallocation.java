package fu.hl.g64.motorflow.receivable.helper;

import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.PaymentAllocation;
import fu.hl.g64.motorflow.receivable.entity.Receivable;
import fu.hl.g64.motorflow.receivable.helper.DebtLedger.Allocation;
import fu.hl.g64.motorflow.receivable.helper.DebtLedger.Debt;
import fu.hl.g64.motorflow.receivable.repository.PaymentAllocationRepository;
import fu.hl.g64.motorflow.receivable.repository.ReceivableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * BR-71: reverses the active allocation rows of a debt payment and allocates the corrected amount again over the
 * same receivables, oldest first (BR-70). Caller holds the payment lock. Returns the new rows.
 */
@Component
@RequiredArgsConstructor
public class PaymentReallocation {

    private final PaymentAllocationRepository allocations;
    private final ReceivableRepository receivables;
    private final DebtLedger ledger;

    public List<PaymentAllocation> reallocate(Payment payment, long correctedAmount, long adjustmentRequestId,
                                            LocalDateTime now) {
        List<PaymentAllocation> rows =
                allocations.findByPaymentIdInAndReversedAtIsNull(List.of(payment.getId()));
        if (rows.isEmpty()) {
            throw new IllegalStateException("no active allocation rows for payment " + payment.getId());
        }
        rows.forEach(row -> row.reverse(adjustmentRequestId, now));
        allocations.saveAllAndFlush(rows);

        List<Long> receivableIds = rows.stream().map(PaymentAllocation::getReceivableId).distinct().toList();
        List<Receivable> locked = receivables.findAllByIdForUpdate(receivableIds);
        locked.sort(Comparator.comparing(Receivable::getRecognizedAt).thenComparing(Receivable::getId));

        long customerId = locked.getFirst().getCustomerId();
        Map<Long, Debt> debts = ledger.debtsOf(customerId, null).stream()
                .filter(debt -> receivableIds.contains(debt.id()))
                .collect(Collectors.toMap(Debt::id, Function.identity()));
        List<Debt> ordered = locked.stream().map(r -> debts.get(r.getId())).filter(Objects::nonNull).toList();

        long owed = ordered.stream().mapToLong(Debt::remaining).sum();
        if (correctedAmount > owed) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG02");
        }

        List<Allocation> plan = DebtLedger.allocate(ledger.inSettlementOrder(ordered), correctedAmount);
        List<PaymentAllocation> fresh = new ArrayList<>();
        for (Allocation share : plan) {
            Receivable receivable = share.debt().receivable();
            fresh.add(PaymentAllocation.of(payment.getId(), receivable.getId(), share.amount()));
            if (share.amount() == share.debt().remaining()) {
                receivable.settle(now);
            } else if (receivable.getSettledAt() != null) {
                receivable.reopen();
            }
        }
        return allocations.saveAll(fresh);
    }
}