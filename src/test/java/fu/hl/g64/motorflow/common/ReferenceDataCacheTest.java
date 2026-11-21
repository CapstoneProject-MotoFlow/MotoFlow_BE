package fu.hl.g64.motorflow.common;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.catalog.dto.PartCategoryRequest;
import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;
import fu.hl.g64.motorflow.catalog.service.PartCategoryService;
import fu.hl.g64.motorflow.pricing.dto.SalesLimitRequest;
import fu.hl.g64.motorflow.pricing.dto.SalesLimitType;
import fu.hl.g64.motorflow.pricing.entity.enums.PolicyType;
import fu.hl.g64.motorflow.pricing.helper.PricingRules;
import fu.hl.g64.motorflow.pricing.service.PricingRuleService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.dto.UpdateStoreRequest;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.service.StoreService;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import fu.hl.g64.motorflow.support.QueryCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** R4 III.2.1: reference data is read from the cache and a change through the service is seen at once. */
class ReferenceDataCacheTest extends ApiTestUsers {

    @Autowired
    private PartCategoryService categories;
    @Autowired
    private StoreService stores;
    @Autowired
    private PricingRuleService pricingRules;
    @Autowired
    private PricingRules rules;
    @Autowired
    private QueryCounter queries;
    @Autowired
    private PlatformTransactionManager transactions;

    private AuthenticatedUser owner;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        UserAccount account = createUser("owner", Role.OWNER, null);
        owner = new AuthenticatedUser(account.getId(), 0, account.getUsername(), account.getFullName(), Role.OWNER,
                null, Set.of(), false);
    }

    @Test
    void categoryTreeIsReadOnceAndAChangeIsSeenAtOnce() {
        categories.create(owner, new PartCategoryRequest("Động cơ", VehicleType.MOTORCYCLE, null, null));
        assertThat(categories.tree(null)).hasSize(1);

        queries.reset();
        assertThat(categories.tree(null)).hasSize(1);
        assertThat(queries.prepared()).isZero();

        // A row written behind the service's back is not seen until the cache is cleared ...
        jdbc.update("INSERT INTO part_category (category_name, vehicle_type) VALUES ('Phanh', 'MOTORCYCLE')");
        assertThat(categories.tree(null)).hasSize(1);
        // ... while a change through the service clears it.
        categories.create(owner, new PartCategoryRequest("Điện", VehicleType.MOTORCYCLE, null, null));
        assertThat(categories.tree(null)).extracting("categoryName").containsExactlyInAnyOrder("Động cơ", "Phanh", "Điện");
    }

    @Test
    void storeRowsAreCachedButManagersAreReadLive() {
        long store = createStore("Cầu Giấy");
        assertThat(stores.listStores(null, null, null)).singleElement()
                .satisfies(s -> assertThat(s.managers()).isEmpty());

        // A new Branch Manager shows at once: managers are not part of the cache.
        createUser("bm", Role.BRANCH_MANAGER, store);
        assertThat(stores.listStores(null, null, null)).singleElement()
                .satisfies(s -> assertThat(s.managers()).extracting("fullName").containsExactly("Test bm"));

        stores.updateStore(owner, store, new UpdateStoreRequest("Cầu Giấy 2", "Hà Nội", null, StoreStatus.ACTIVE, false, null));
        assertThat(stores.listStores(null, null, null)).singleElement()
                .satisfies(s -> assertThat(s.storeName()).isEqualTo("Cầu Giấy 2"));
    }

    @Test
    void aScheduledRuleStartsAtItsTimeAndASavedRuleIsSeenAtOnce() {
        LocalDate today = LocalDate.now(clock);
        pricingRules.setSalesLimit(owner, new SalesLimitRequest(SalesLimitType.DISCOUNT_THRESHOLD, BigDecimal.TEN, today));
        pricingRules.setSalesLimit(owner,
                new SalesLimitRequest(SalesLimitType.DISCOUNT_THRESHOLD, new BigDecimal("15"), today.plusDays(2)));
        assertThat(rules.rateInEffect(PolicyType.DISCOUNT_THRESHOLD, LocalDateTime.now(clock))).isEqualByComparingTo("10");

        queries.reset();
        rules.rateInEffect(PolicyType.DISCOUNT_THRESHOLD, LocalDateTime.now(clock));
        assertThat(queries.prepared()).isZero();

        // The scheduled version is already in the cache and applies from its day, with no save in between.
        clock.advance(Duration.ofDays(2));
        assertThat(rules.rateInEffect(PolicyType.DISCOUNT_THRESHOLD, LocalDateTime.now(clock))).isEqualByComparingTo("15");

        pricingRules.setSalesLimit(owner,
                new SalesLimitRequest(SalesLimitType.DISCOUNT_THRESHOLD, new BigDecimal("12"), LocalDate.now(clock)));
        assertThat(rules.rateInEffect(PolicyType.DISCOUNT_THRESHOLD, LocalDateTime.now(clock))).isEqualByComparingTo("12");
    }

    @Test
    void aReaderThatSawTheOldRowsCannotPutThemBackAfterTheWriterCommits() throws Exception {
        categories.create(owner, new PartCategoryRequest("Động cơ", VehicleType.MOTORCYCLE, null, null));
        TransactionTemplate tx = new TransactionTemplate(transactions);
        CountDownLatch readerHasRead = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);

        // The reader reads while the writer has not committed, and its transaction ends only after the writer's.
        CompletableFuture<Integer> reader = new CompletableFuture<>();
        Thread readerThread = new Thread(() -> {
            try {
                reader.complete(tx.execute(status -> {
                    int seen = categories.tree(null).size();
                    readerHasRead.countDown();
                    await(writerCommitted);
                    return seen;
                }));
            } catch (RuntimeException e) {
                reader.completeExceptionally(e);
            }
        });
        tx.executeWithoutResult(status -> {
            categories.create(owner, new PartCategoryRequest("Điện", VehicleType.MOTORCYCLE, null, null));
            readerThread.start();
            await(readerHasRead);
        });
        writerCommitted.countDown();

        assertThat(reader.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(categories.tree(null)).extracting("categoryName").containsExactlyInAnyOrder("Động cơ", "Điện");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
