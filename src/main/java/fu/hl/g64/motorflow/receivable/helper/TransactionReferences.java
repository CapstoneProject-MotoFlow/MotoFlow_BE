package fu.hl.g64.motorflow.receivable.helper;

import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * BR-45: a transaction reference is recorded once among the payments and refunds that were not rejected, whatever the
 * case of its letters. Customer debt payments and sales order payments and refunds all save through here.
 */
@Component
@RequiredArgsConstructor
public class TransactionReferences {

    /** V3: the database side of the same rule, for two requests that pass {@link #requireUnused} at the same moment. */
    static final String UNIQUE_INDEX = "ux_payment_reference";

    private final PaymentRepository payments;
    private final PlatformTransactionManager transactionManager;

    /** The reference as typed without surrounding spaces, or null when nothing was typed. */
    public static String clean(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    /** MSG151 when a payment or refund that was not rejected already carries the reference. */
    public void requireUnused(String reference) {
        if (reference != null) {
            payments.findByTransactionReference(reference).stream().findFirst().ifPresent(existing -> {
                throw used(reference, existing.getId());
            });
        }
    }

    /**
     * Inserts the payment at once, so that another request that saved the same reference after this one passed
     * {@link #requireUnused} is answered with MSG151 rather than a server error.
     */
    public Payment save(Payment payment) {
        try {
            return payments.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            String reference = payment.getTransactionReference();
            if (reference == null || !String.valueOf(e.getMostSpecificCause().getMessage()).contains(UNIQUE_INDEX)) {
                throw e;
            }
            // This transaction can no longer read, and the other request has committed: find its payment in a new one.
            TransactionTemplate lookup = new TransactionTemplate(transactionManager);
            lookup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            lookup.setReadOnly(true);
            Long existing = lookup.execute(status -> payments.findByTransactionReference(reference).stream()
                    .findFirst().map(Payment::getId).orElse(null));
            throw used(reference, existing);
        }
    }

    private static BusinessException used(String reference, Long paymentId) {
        return new BusinessException(HttpStatus.CONFLICT, "MSG151", reference,
                paymentId == null ? "?" : String.valueOf(paymentId));
    }
}
