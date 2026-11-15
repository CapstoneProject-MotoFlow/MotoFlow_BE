package fu.hl.g64.motorflow.receivable;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.helper.DebtLedger;
import fu.hl.g64.motorflow.receivable.helper.DebtLedger.Debt;
import fu.hl.g64.motorflow.receivable.helper.PaymentReallocation;
import fu.hl.g64.motorflow.receivable.repository.PaymentRepository;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BR-71: reversing allocation rows and splitting a corrected debt payment again (BR-70). */
class PaymentReallocationTest extends ApiTestUsers {

    @Autowired
    private PaymentReallocation reallocation;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private DebtLedger ledger;
    @Autowired
    private SalesOrderRepository salesOrders;

    private UserAccount owner;
    private long store;
    private long customer;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        owner = createUser("owner", Role.OWNER, null);
        store = createStore("Gia Lâm");
        customer = jdbc.queryForObject("INSERT INTO customer (customer_name, phone, created_by) VALUES ('Hùng', '0900', ?) "
                + "RETURNING id", Long.class, owner.getId());
    }

    private record Fixture(long receivable1, long receivable2, long payment, long adjustment) {
    }

    private Fixture seedPayment() {
        LocalDateTime t1 = LocalDateTime.now(clock).minusDays(2);
        LocalDateTime t2 = LocalDateTime.now(clock).minusDays(1);
        long receivable1 = jdbc.queryForObject("INSERT INTO receivable (origin_type, customer_id, store_id, original_amount, "
                        + "recognized_by, recognized_at) VALUES ('OPENING_BALANCE', ?, ?, 500000, ?, ?) RETURNING id",
                Long.class, customer, store, owner.getId(), t1);
        long receivable2 = jdbc.queryForObject("INSERT INTO receivable (origin_type, customer_id, store_id, original_amount, "
                        + "recognized_by, recognized_at) VALUES ('OPENING_BALANCE', ?, ?, 2400000, ?, ?) RETURNING id",
                Long.class, customer, store, owner.getId(), t2);
        LocalDateTime paidAt = LocalDateTime.now(clock);
        long payment = jdbc.queryForObject("INSERT INTO payment (payment_type, amount, payment_method, status, recorded_by, "
                        + "recorded_at, verified_by, verified_at) VALUES ('DEBT_PAYMENT', 1000000, 'CASH', 'VERIFIED', ?, ?, "
                        + "?, ?) RETURNING id", Long.class, owner.getId(), paidAt, owner.getId(), paidAt);
        jdbc.update("INSERT INTO payment_allocation (payment_id, receivable_id, allocated_amount) VALUES (?, ?, 500000)",
                payment, receivable1);
        jdbc.update("INSERT INTO payment_allocation (payment_id, receivable_id, allocated_amount) VALUES (?, ?, 500000)",
                payment, receivable2);
        jdbc.update("UPDATE receivable SET settled_at = ? WHERE id = ?", paidAt, receivable1);
        long adjustment = jdbc.queryForObject("INSERT INTO adjustment_request (adjustment_type, store_id, payment_id, reason, "
                        + "status, requested_by) VALUES ('DOCUMENT_CORRECTION', ?, ?, 'Ghi sai', 'APPLIED', ?) RETURNING id",
                Long.class, store, payment, owner.getId());
        return new Fixture(receivable1, receivable2, payment, adjustment);
    }

    private long remaining(long receivableId) {
        return ledger.debtsOf(customer, store).stream().filter(debt -> debt.id() == receivableId)
                .mapToLong(Debt::remaining).findFirst().orElseThrow();
    }

    @Test
    @Transactional
    void reallocateCorrectsAllocationsAndRemaining() {
        Fixture fixture = seedPayment();
        Payment payment = payments.findById(fixture.payment()).orElseThrow();
        LocalDateTime now = LocalDateTime.now(clock);

        List<?> rows = reallocation.reallocate(payment, 600_000, fixture.adjustment(), now);
        assertThat(rows).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_allocation WHERE payment_id = ? "
                + "AND reversed_at IS NOT NULL", Long.class, fixture.payment())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_allocation WHERE payment_id = ? "
                + "AND reversed_at IS NULL", Long.class, fixture.payment())).isEqualTo(2);
        assertThat(remaining(fixture.receivable1())).isZero();
        assertThat(remaining(fixture.receivable2())).isEqualTo(2_300_000);

        reallocation.reallocate(payment, 300_000, fixture.adjustment(), now);
        assertThat(remaining(fixture.receivable1())).isEqualTo(200_000);
        assertThat(jdbc.queryForObject("SELECT settled_at FROM receivable WHERE id = ?", Object.class,
                fixture.receivable1())).isNull();
    }

    @Test
    void partialUniqueIndexBlocksTwoActiveRowsForSamePair() {
        Fixture fixture = seedPayment();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO payment_allocation (payment_id, receivable_id, allocated_amount) VALUES (?, ?, 1)",
                fixture.payment(), fixture.receivable1()))
                .satisfiesAnyOf(
                        ex -> assertThat(ex).isInstanceOf(DataIntegrityViolationException.class),
                        ex -> assertThat(ex).isInstanceOf(UncategorizedSQLException.class));
        jdbc.update("UPDATE payment_allocation SET reversed_at = now(), reversed_by_adjustment_id = ? "
                        + "WHERE payment_id = ? AND receivable_id = ?",
                fixture.adjustment(), fixture.payment(), fixture.receivable1());
        jdbc.update("INSERT INTO payment_allocation (payment_id, receivable_id, allocated_amount) VALUES (?, ?, 100000)",
                fixture.payment(), fixture.receivable1());
    }

    @Test
    void sumDebtCollectedIgnoresReversedRows() {
        Fixture fixture = seedPayment();
        LocalDateTime from = LocalDateTime.now(clock).minusHours(1);
        LocalDateTime to = LocalDateTime.now(clock).plusHours(1);
        long before = salesOrders.sumDebtCollectedByStore(store, from, to).stream()
                .mapToLong(row -> ((Number) row[1]).longValue()).sum();
        assertThat(before).isEqualTo(1_000_000);

        jdbc.update("UPDATE payment_allocation SET reversed_at = now(), reversed_by_adjustment_id = ? "
                        + "WHERE payment_id = ? AND receivable_id = ?",
                fixture.adjustment(), fixture.payment(), fixture.receivable2());
        long after = salesOrders.sumDebtCollectedByStore(store, from, to).stream()
                .mapToLong(row -> ((Number) row[1]).longValue()).sum();
        assertThat(after).isEqualTo(500_000);
    }
}