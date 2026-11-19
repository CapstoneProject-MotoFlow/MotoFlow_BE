package fu.hl.g64.motorflow.support.unit;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import org.assertj.core.api.ThrowableAssert;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Report 5.1 unit tests: plain objects for the mocks to return, and the fixed "today" 18/11/2026. */
public final class UnitFixtures {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final LocalDate TODAY = LocalDate.of(2026, 11, 18);
    public static final LocalDateTime NOW = TODAY.atTime(9, 0);
    public static final Clock CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);

    private UnitFixtures() {
    }

    /** Sets the database id (and any other private field) the way JPA would. */
    public static <T> T with(T entity, String field, Object value) {
        ReflectionTestUtils.setField(entity, field, value);
        return entity;
    }

    public static <T> T withId(T entity, long id) {
        return with(entity, "id", id);
    }

    public static AuthenticatedUser auth(long id, Role role, Long storeId) {
        return new AuthenticatedUser(id, 1L, "user" + id, "User " + id, role, storeId, Set.of(), false);
    }

    public static Store store(long id, StoreStatus status, boolean central) {
        Store store = withId(Store.create("Store " + id, "Address " + id, null, central, NOW), id);
        return with(store, "status", status);
    }

    public static Product product(long id, String sku, ProductStatus status) {
        Product part = withId(Product.create(sku, "Part " + sku, 1L, null, null, "cai", null, 1L, NOW), id);
        return with(part, "status", status);
    }

    public static UserAccount account(long id, Role role, Long storeId) {
        return withId(UserAccount.create("user" + id, "hash", "User " + id, role, storeId, 1L, NOW), id);
    }

    /** The call fails with this HTTP status and message code. */
    public static void assertBusiness(ThrowableAssert.ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            org.assertj.core.api.Assertions.assertThat(e.getStatus()).isEqualTo(status);
            org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo(code);
        });
    }
}
