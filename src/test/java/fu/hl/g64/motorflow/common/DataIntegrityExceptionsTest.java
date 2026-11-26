package fu.hl.g64.motorflow.common;

import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.common.error.DataIntegrityExceptions;
import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.helper.TransactionReferences;
import fu.hl.g64.motorflow.receivable.repository.PaymentRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataIntegrityExceptionsTest {

    @Test
    void findsConstraintNameInsideBatchUpdateNextExceptionChain() {
        SQLException firstDetail = new SQLException("Batch entry 0 failed on another column");
        SQLException secondDetail = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"ux_supplier_name_ci\"",
                "23505");
        firstDetail.setNextException(secondDetail);

        BatchUpdateException batchEx = new BatchUpdateException(
                "Batch entry 0 was aborted. Call getNextException to see other errors in the batch.",
                "23505",
                new int[] {java.sql.Statement.EXECUTE_FAILED});
        batchEx.setNextException(firstDetail);

        ConstraintViolationException hibernateEx = new ConstraintViolationException(
                "could not execute batch", batchEx, null);
        DataIntegrityViolationException springEx = new DataIntegrityViolationException(
                "could not execute batch", hibernateEx);

        assertThat(DataIntegrityExceptions.mentionsConstraint(springEx, "ux_supplier_name_ci")).isTrue();
        assertThat(DataIntegrityExceptions.mentionsConstraint(springEx, "ux_payment_reference")).isFalse();
    }

    @Test
    void findsConstraintNameOnHibernateConstraintViolationEvenWhenMessageDiffers() {
        SQLException sqlEx = new SQLException("duplicate key", "23505");
        ConstraintViolationException hibernateEx = new ConstraintViolationException(
                "could not execute batch", sqlEx, "ux_payment_reference");
        DataIntegrityViolationException springEx = new DataIntegrityViolationException(
                "could not execute batch", hibernateEx);

        assertThat(DataIntegrityExceptions.mentionsConstraint(springEx, "ux_payment_reference")).isTrue();
    }

    @Test
    void transactionReferencesRecognizesUxPaymentReferenceInsideBatchUpdateException() {
        PaymentRepository payments = mock(PaymentRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        Payment incoming = Payment.verifiedDebtPayment(100_000, null, PaymentMethod.BANK_TRANSFER, "FT-BATCH",
                1L, LocalDateTime.of(2026, 10, 13, 10, 0));
        Payment existing = mock(Payment.class);
        when(existing.getId()).thenReturn(77L);
        when(payments.findByTransactionReference("FT-BATCH")).thenReturn(List.of(existing));

        SQLException detail = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"ux_payment_reference\"", "23505");
        BatchUpdateException batchEx = new BatchUpdateException(
                "Batch entry 0 was aborted. Call getNextException to see other errors in the batch.",
                "23505", new int[] {java.sql.Statement.EXECUTE_FAILED});
        batchEx.setNextException(detail);
        when(payments.saveAndFlush(incoming)).thenThrow(new DataIntegrityViolationException(
                "could not execute batch", new ConstraintViolationException("could not execute batch", batchEx, null)));

        TransactionReferences references = new TransactionReferences(payments, txManager);
        assertThatThrownBy(() -> references.save(incoming))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException biz = (BusinessException) ex;
                    assertThat(biz.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(biz.getCode()).isEqualTo("MSG151");
                    assertThat(biz.getArgs()).containsExactly("FT-BATCH", "77");
                });
    }
}
