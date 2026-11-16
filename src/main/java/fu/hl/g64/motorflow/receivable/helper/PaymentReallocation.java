package fu.hl.g64.motorflow.receivable.helper;

import fu.hl.g64.motorflow.adjustment.entity.AdjustmentRequest;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import fu.hl.g64.motorflow.adjustment.repository.AdjustmentRequestRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.partner.repository.CustomerRepository;
import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.PaymentAllocation;
import fu.hl.g64.motorflow.receivable.entity.Receivable;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;
import fu.hl.g64.motorflow.receivable.helper.DebtLedger.Allocation;
import fu.hl.g64.motorflow.receivable.helper.DebtLedger.Debt;
import fu.hl.g64.motorflow.receivable.repository.PaymentAllocationRepository;
import fu.hl.g64.motorflow.receivable.repository.ReceivableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    private final CustomerRepository customers;
    private final AdjustmentRequestRepository adjustments;
    private final AuditLogRepository auditLog;

    @Transactional(propagation = Propagation.MANDATORY)
    public List<PaymentAllocation> reallocate(Payment payment, long correctedAmount, long adjustmentRequestId,
                                            LocalDateTime now) {
        if (correctedAmount <= 0 || payment.getPaymentType() != PaymentType.DEBT_PAYMENT
                || payment.getStatus() != PaymentStatus.VERIFIED) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG270");
        }
        AdjustmentRequest adjustment = adjustments.findById(adjustmentRequestId)
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "MSG270"));
        if (adjustment.getStatus() != AdjustmentRequestStatus.APPLIED
                || !Objects.equals(adjustment.getPaymentId(), payment.getId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG270");
        }
        List<PaymentAllocation> rows =
                allocations.findByPaymentIdInAndReversedAtIsNull(List.of(payment.getId()));
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG270");
        }
        rows.forEach(row -> row.reverse(adjustmentRequestId, now));
        allocations.saveAllAndFlush(rows);

        List<Long> receivableIds = rows.stream().map(PaymentAllocation::getReceivableId).distinct().toList();
        List<Receivable> locked = receivables.findAllByIdForUpdate(receivableIds);
        locked.sort(Comparator.comparing(Receivable::getRecognizedAt).thenComparing(Receivable::getId));

        long customerId = locked.getFirst().getCustomerId();
        customers.findByIdForUpdate(customerId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG74"));
        Map<Long, Debt> debts = ledger.debtsOf(customerId, null).stream()
                .filter(debt -> receivableIds.contains(debt.id()))
                .collect(Collectors.toMap(Debt::id, Function.identity()));
        List<Debt> ordered = locked.stream().map(r -> debts.get(r.getId())).filter(Objects::nonNull).toList();

        long owed = ordered.stream().mapToLong(Debt::remaining).sum();
        if (correctedAmount > owed) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG150", owed);
        }

        List<Allocation> plan = DebtLedger.allocate(ledger.inSettlementOrder(ordered), correctedAmount);
        Map<Long, Long> given = plan.stream()
                .collect(Collectors.toMap(share -> share.debt().id(), Allocation::amount));
        List<PaymentAllocation> fresh = new ArrayList<>();
        for (Receivable receivable : locked) {
            long amount = given.getOrDefault(receivable.getId(), 0L);
            Debt debt = debts.get(receivable.getId());
            if (debt == null) {
                continue;
            }
            if (amount > 0) {
                fresh.add(PaymentAllocation.of(payment.getId(), receivable.getId(), amount));
            }
            if (amount > 0 && amount == debt.remaining()) {
                receivable.settle(now);
            } else if (receivable.getSettledAt() != null) {
                receivable.reopen();
            }
        }
        auditLog.save(AuditLog.record("PAYMENT", payment.getId(), "PAYMENT_REALLOCATED", payment.getRecordedBy(),
                null, null, Map.of("corrected_amount", correctedAmount, "adjustment_id", adjustmentRequestId), now));
        return allocations.saveAll(fresh);
    }
}