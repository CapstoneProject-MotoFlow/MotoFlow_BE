package fu.hl.g64.motorflow.receivable.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.partner.entity.Customer;
import fu.hl.g64.motorflow.partner.entity.enums.CreditStatus;
import fu.hl.g64.motorflow.partner.entity.enums.EventType;
import fu.hl.g64.motorflow.partner.repository.CustomerRepository;
import fu.hl.g64.motorflow.receivable.dto.CreditDecisionRequest;
import fu.hl.g64.motorflow.receivable.dto.CustomerCreditResponse;
import fu.hl.g64.motorflow.receivable.helper.DebtLedger;
import fu.hl.g64.motorflow.receivable.service.CustomerCreditService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CustomerCreditServiceImpl implements CustomerCreditService {

    /** The audit_log rows of the customer profile's credit history (UC-28): CREDIT_ + event type. */
    private static final String CUSTOMER = "CUSTOMER";
    private static final String GRANTED = "CREDIT_" + EventType.GRANTED;
    private static final String REVOKED = "CREDIT_" + EventType.REVOKED;

    private final CustomerRepository customers;
    private final UserAccountRepository users;
    private final AuditLogRepository auditLog;
    private final DebtLedger ledger;
    private final Clock clock;

    @Transactional
    @Override
    public CustomerCreditResponse grant(AuthenticatedUser owner, long customerId, CreditDecisionRequest request) {
        Customer customer = lock(customerId);
        boolean changed = customer.getCreditStatus() != CreditStatus.ELIGIBLE;
        if (changed) {
            record(owner, customer, GRANTED, request.reason());
            customer.grantCredit(LocalDateTime.now(clock));
        }
        return response(customer, changed);
    }

    @Transactional
    @Override
    public CustomerCreditResponse revoke(AuthenticatedUser owner, long customerId, CreditDecisionRequest request) {
        Customer customer = lock(customerId);
        if (customer.getCreditStatus() == CreditStatus.NOT_GRANTED) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG146", customer.getCustomerName());
        }
        boolean changed = customer.getCreditStatus() == CreditStatus.ELIGIBLE;
        if (changed) {
            record(owner, customer, REVOKED, request.reason());
            customer.revokeCredit(LocalDateTime.now(clock));
        }
        return response(customer, changed);
    }

    /** One decision row with the status before and after, so the history shows what changed. */
    private void record(AuthenticatedUser owner, Customer customer, String action, String reason) {
        String after = GRANTED.equals(action) ? CreditStatus.ELIGIBLE.name() : CreditStatus.REVOKED.name();
        auditLog.save(AuditLog.record(CUSTOMER, customer.getId(), action, owner.id(), reason.strip(),
                Map.of("credit_status", customer.getCreditStatus().name()), Map.of("credit_status", after),
                LocalDateTime.now(clock)));
    }

    private CustomerCreditResponse response(Customer customer, boolean changed) {
        List<AuditLog> decisions = auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(CUSTOMER,
                customer.getId(), Set.of(GRANTED, REVOKED));
        CustomerCreditResponse.Decision last = decisions.stream().findFirst().map(entry -> new CustomerCreditResponse.Decision(
                        GRANTED.equals(entry.getAction()) ? EventType.GRANTED : EventType.REVOKED, entry.getReason(),
                        entry.getActorId(), entry.getActorId() == null ? null
                        : users.findById(entry.getActorId()).map(UserAccount::getFullName).orElse(null),
                        entry.getOccurredAt()))
                .orElse(null);
        LocalDate today = ledger.today();
        List<DebtLedger.Debt> debts = ledger.debtsOf(customer.getId(), null);
        return new CustomerCreditResponse(customer.getId(), customer.getCustomerName(), customer.getCreditStatus(),
                changed, last, debts.stream().mapToLong(DebtLedger.Debt::remaining).sum(),
                debts.stream().filter(debt -> debt.overdue(today)).mapToLong(DebtLedger.Debt::remaining).sum());
    }

    private Customer lock(long customerId) {
        return customers.findByIdForUpdate(customerId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG74"));
    }
}
