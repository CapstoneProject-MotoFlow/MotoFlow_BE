package fu.hl.g64.motorflow.inventory;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.inventory.job.LowStockWarningJob;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** JOB-02. */
class LowStockWarningJobTest extends ApiTestUsers {

    @Autowired
    private LowStockWarningJob job;

    private UserAccount owner;
    private UserAccount bm;
    private UserAccount bm2;
    private UserAccount otherBm;
    private long branch;
    private long other;
    private long category;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        branch = createStore("Long Biên");
        other = createStore("Hoàng Mai");
        owner = createUser("owner", Role.OWNER, null);
        bm = createUser("bm", Role.BRANCH_MANAGER, branch);
        bm2 = createUser("bm2", Role.BRANCH_MANAGER, branch);
        otherBm = createUser("bmhm", Role.BRANCH_MANAGER, other);
        createUser("staff", Role.STAFF, branch);
        category = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Phanh', 'MOTORCYCLE') RETURNING id", Long.class);
    }

    @Test
    void managersOfTheStoreAreToldAboutEachPartBelowThreshold() {
        long brake = part("MA-PHANH", "ACTIVE");
        long chain = part("XICH-XE", "ACTIVE");
        long oil = part("NHOT", "ACTIVE");
        balance(branch, brake, 2, 5);
        balance(branch, chain, 0, 3);
        balance(branch, oil, 10, 5);

        assertThat(job.run()).isEqualTo(4);

        assertThat(sentTo(bm)).containsExactlyInAnyOrder(brake, chain);
        assertThat(sentTo(bm2)).containsExactlyInAnyOrder(brake, chain);
        assertThat(sentTo(otherBm)).isEmpty();
        assertThat(sentTo(owner)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT message FROM notification WHERE recipient_id = ? AND ref_id = ?",
                String.class, bm.getId(), brake))
                .isEqualTo("Phụ tùng MA-PHANH - Phụ tùng MA-PHANH dưới ngưỡng tồn tối thiểu: còn 2, ngưỡng 5.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE notification_type <> 'LOW_STOCK' "
                + "OR ref_type <> 'PRODUCT'", Integer.class)).isZero();
    }

    @Test
    void runningAgainWithNothingChangedSendsNothing() {
        long brake = part("MA-PHANH", "ACTIVE");
        balance(branch, brake, 2, 5);
        job.run();

        assertThat(job.run()).isZero();
        assertThat(sentTo(bm)).containsExactly(brake);
    }

    @Test
    void anotherPartFallingBelowIsToldOnItsOwn() {
        long brake = part("MA-PHANH", "ACTIVE");
        long chain = part("XICH-XE", "ACTIVE");
        balance(branch, brake, 2, 5);
        balance(branch, chain, 8, 3);
        job.run();

        jdbc.update("UPDATE stock_balance SET available_qty = 1 WHERE product_id = ?", chain);
        assertThat(job.run()).isEqualTo(2);
        assertThat(sentTo(bm)).containsExactlyInAnyOrder(brake, chain);
    }

    @Test
    void stillLowAfterTheManagerReadItIsToldAgain() {
        long brake = part("MA-PHANH", "ACTIVE");
        balance(branch, brake, 2, 5);
        job.run();

        jdbc.update("UPDATE notification SET is_read = TRUE, read_at = now() WHERE recipient_id = ?", bm.getId());
        assertThat(job.run()).isEqualTo(1);
        assertThat(sentTo(bm)).containsExactly(brake, brake);
        assertThat(sentTo(bm2)).containsExactly(brake);
    }

    @Test
    void discontinuedPartsInactiveStoresAndUnsetThresholdsAreSkipped() {
        long discontinued = part("CU", "DISCONTINUED");
        long noThreshold = part("KHONG-NGUONG", "ACTIVE");
        long brake = part("MA-PHANH", "ACTIVE");
        balance(branch, discontinued, 0, 5);
        jdbc.update("INSERT INTO stock_balance (store_id, product_id, available_qty, updated_at) VALUES (?, ?, 0, now())",
                branch, noThreshold);
        balance(other, brake, 0, 5);
        jdbc.update("UPDATE store SET status = 'INACTIVE' WHERE id = ?", other);

        assertThat(job.run()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Integer.class)).isZero();
    }

    private long part(String sku, String status) {
        return jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, status, created_by) "
                + "VALUES (?, ?, ?, 'cái', ?, ?) RETURNING id", Long.class, sku, "Phụ tùng " + sku, category, status,
                owner.getId());
    }

    private void balance(long store, long product, int available, int threshold) {
        jdbc.update("INSERT INTO stock_balance (store_id, product_id, available_qty, low_stock_threshold, updated_at) "
                + "VALUES (?, ?, ?, ?, now())", store, product, available, threshold);
    }

    private List<Long> sentTo(UserAccount user) {
        return jdbc.queryForList("SELECT ref_id FROM notification WHERE recipient_id = ? ORDER BY id", Long.class,
                user.getId());
    }
}
