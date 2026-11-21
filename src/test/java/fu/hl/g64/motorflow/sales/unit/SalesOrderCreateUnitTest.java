package fu.hl.g64.motorflow.sales.unit;

import fu.hl.g64.motorflow.sales.dto.SalesOrderDetail;
import fu.hl.g64.motorflow.sales.dto.SalesOrderLineRequest;
import fu.hl.g64.motorflow.sales.dto.SalesOrderRequest;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.enums.PriceType;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static fu.hl.g64.motorflow.support.unit.UnitFixtures.assertBusiness;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.auth;
import static fu.hl.g64.motorflow.support.unit.UnitFixtures.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Report 5.1, sheet SalesOrder.create: SalesOrderServiceImpl.create(seller, request), UTCID01-24. Discount threshold
 * in effect 10%; store 2 can sell 5 of every part unless a test says otherwise.
 */
class SalesOrderCreateUnitTest extends SalesOrderUnitTestBase {

    private static SalesOrderLineRequest line(long productId, int quantity) {
        return new SalesOrderLineRequest(productId, quantity);
    }

    private static SalesOrderRequest walkIn(Long storeId, SalesOrderLineRequest... lines) {
        return new SalesOrderRequest(storeId, null, " Anh Nam ", "0909000111", PriceType.RETAIL, null, null,
                List.of(lines));
    }

    private static SalesOrderRequest wholesale(String rate, SalesOrderLineRequest... lines) {
        return new SalesOrderRequest(null, 20L, null, null, PriceType.WHOLESALE, rate == null ? null
                : new BigDecimal(rate), null, List.of(lines));
    }

    private void assertRefused(SalesOrderRequest request, HttpStatus status, String code) {
        assertRefused(manager, request, status, code);
    }

    private void assertRefused(fu.hl.g64.motorflow.security.AuthenticatedUser seller, SalesOrderRequest request,
                               HttpStatus status, String code) {
        assertBusiness(() -> service.create(seller, request), status, code);
        // BR-16 is checked once the order row exists; the transaction then rolls it back with nothing on it.
        verify(orderLines, never()).saveAll(any());
        verify(auditLog, never()).save(any());
    }

    private static long priceOf(SalesOrderDetail order, long productId) {
        return order.lines().stream().filter(l -> l.productId() == productId).findFirst().orElseThrow()
                .actualUnitPrice();
    }

    @Test
    @DisplayName("UTCID01 N: walk-in \" Anh Nam \", oil 2 and filter 1 at retail -> total 270,000")
    void utc01() {
        SalesOrderDetail order = service.create(manager, walkIn(null, line(OIL, 2), line(FILTER, 1)));

        assertThat(order.id()).isEqualTo(10);
        assertThat(order.status()).isEqualTo(SalesOrderStatus.AWAITING_PICKING);
        assertThat(order.customerName()).isEqualTo("Anh Nam");
        assertThat(order.total()).isEqualTo(270_000);
        assertThat(priceOf(order, OIL)).isEqualTo(110_000);
        assertThat(priceOf(order, FILTER)).isEqualTo(50_000);
        assertThat(order.split()).isNull();
    }

    @Test
    @DisplayName("UTCID02 N: Owner sells at store 2 to customer 20, wholesale 5% -> oil at 104,500")
    void utc02() {
        SalesOrderDetail order = service.create(owner, new SalesOrderRequest(2L, 20L, null, null,
                PriceType.WHOLESALE, new BigDecimal("5"), null, List.of(line(OIL, 2))));

        assertThat(order.id()).isEqualTo(10);
        assertThat(order.status()).isEqualTo(SalesOrderStatus.AWAITING_PICKING);
        assertThat(order.customerId()).isEqualTo(20);
        assertThat(order.customerName()).isEqualTo("Garage Hùng");
        assertThat(order.priceType()).isEqualTo(PriceType.WHOLESALE);
        assertThat(priceOf(order, OIL)).isEqualTo(104_500);
        verify(notifications).notifyStoreManagers(eq(2L), eq("SALES_ORDER_CREATED"), anyString(), anyString(),
                eq(10L));
    }

    @Test
    @DisplayName("UTCID03 B: wholesale 9.99%, just below the threshold -> filter at 45,005")
    void utc03() {
        SalesOrderDetail order = service.create(manager, wholesale("9.99", line(FILTER, 1)));

        assertThat(order.id()).isEqualTo(10);
        assertThat(order.status()).isEqualTo(SalesOrderStatus.AWAITING_PICKING);
        assertThat(priceOf(order, FILTER)).isEqualTo(45_005);
    }

    @Test
    @DisplayName("UTCID04 B: wholesale with no discount while no threshold is in effect -> accepted")
    void utc04() {
        threshold = null;

        SalesOrderDetail order = service.create(manager, wholesale(null, line(OIL, 1)));

        assertThat(order.id()).isEqualTo(10);
        assertThat(order.status()).isEqualTo(SalesOrderStatus.AWAITING_PICKING);
        assertThat(order.priceType()).isEqualTo(PriceType.WHOLESALE);
    }

    @Test
    @DisplayName("UTCID05 A: Owner names no store -> MSG157")
    void utc05() {
        assertRefused(owner, walkIn(null, line(OIL, 1)), HttpStatus.BAD_REQUEST, "MSG157");
    }

    @Test
    @DisplayName("UTCID06 A: Branch Manager of store 2 names store 9 -> MSG05")
    void utc06() {
        assertRefused(walkIn(9L, line(OIL, 1)), HttpStatus.FORBIDDEN, "MSG05");
    }

    @Test
    @DisplayName("UTCID07 A: store 2 is INACTIVE -> MSG46")
    void utc07() {
        with(gialam, "status", StoreStatus.INACTIVE);

        assertRefused(walkIn(null, line(OIL, 1)), HttpStatus.BAD_REQUEST, "MSG46");
    }

    @Test
    @DisplayName("UTCID08 A: customer 404 does not exist -> MSG74")
    void utc08() {
        assertRefused(new SalesOrderRequest(null, 404L, null, null, PriceType.RETAIL, null, null,
                List.of(line(OIL, 1))), HttpStatus.BAD_REQUEST, "MSG74");
    }

    @Test
    @DisplayName("UTCID09 A: a retail order with 5% discount -> MSG161")
    void utc09() {
        assertRefused(new SalesOrderRequest(null, null, null, null, PriceType.RETAIL, new BigDecimal("5"), null,
                List.of(line(OIL, 1))), HttpStatus.BAD_REQUEST, "MSG161");
    }

    @Test
    @DisplayName("UTCID10 A: oil on two lines -> MSG158 for line 2")
    void utc10() {
        assertRefused(walkIn(null, line(OIL, 1), line(OIL, 2)), HttpStatus.BAD_REQUEST, "MSG158");
    }

    @Test
    @DisplayName("UTCID11 A: part 999 does not exist -> MSG57")
    void utc11() {
        assertRefused(walkIn(null, line(999, 1)), HttpStatus.BAD_REQUEST, "MSG57");
    }

    @Test
    @DisplayName("UTCID12 A: part 300 CU-01 is discontinued -> MSG159")
    void utc12() {
        assertRefused(walkIn(null, line(OLD, 1)), HttpStatus.BAD_REQUEST, "MSG159");
    }

    @Test
    @DisplayName("UTCID13 A: part 400 MOI-01 has no retail price -> MSG160")
    void utc13() {
        assertRefused(walkIn(null, line(NEW_PART, 1)), HttpStatus.BAD_REQUEST, "MSG160");
    }

    @Test
    @DisplayName("UTCID14 B: wholesale 10%, at the threshold -> MSG162")
    void utc14() {
        assertRefused(wholesale("10", line(OIL, 1)), HttpStatus.BAD_REQUEST, "MSG162");
    }

    @Test
    @DisplayName("UTCID15 A: wholesale 5% while no threshold is in effect -> MSG163")
    void utc15() {
        threshold = null;

        assertRefused(wholesale("5", line(OIL, 1)), HttpStatus.BAD_REQUEST, "MSG163");
    }

    @Test
    @DisplayName("UTCID16 N: Branch Manager names their own store 2 -> accepted")
    void utc16() {
        SalesOrderDetail order = service.create(manager, walkIn(2L, line(OIL, 1)));

        assertThat(order.id()).isEqualTo(10);
        assertThat(order.status()).isEqualTo(SalesOrderStatus.AWAITING_PICKING);
        assertThat(order.storeId()).isEqualTo(2);
        assertThat(order.lines()).singleElement().satisfies(l -> {
            assertThat(l.productId()).isEqualTo(OIL);
            assertThat(l.requestedQty()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("UTCID17 B: wholesale 100%, unit price 0 -> MSG162 (threshold 10%)")
    void utc17() {
        assertRefused(wholesale("100", line(OIL, 1)), HttpStatus.BAD_REQUEST, "MSG162");
    }

    @Test
    @DisplayName("UTCID18 B: wholesale 100% while no threshold is in effect -> MSG163")
    void utc18() {
        threshold = null;

        assertRefused(wholesale("100", line(OIL, 1)), HttpStatus.BAD_REQUEST, "MSG163");
    }

    @Test
    @DisplayName("UTCID19 B: oil wanted 2, taken now 3 -> MSG305")
    void utc19() {
        assertRefused(walkIn(null, new SalesOrderLineRequest(OIL, 2, 3, null)), HttpStatus.BAD_REQUEST, "MSG305");
    }

    @Test
    @DisplayName("UTCID20 A: oil taken now 0 and the customer does not wait -> MSG306")
    void utc20() {
        assertRefused(walkIn(null, new SalesOrderLineRequest(OIL, 2, 0, false)), HttpStatus.BAD_REQUEST,
                "MSG306");
    }

    @Test
    @DisplayName("UTCID21 B: no oil sellable and the customer does not wait -> MSG171")
    void utc21() {
        sellable.put(OIL, 0);

        assertRefused(walkIn(null, new SalesOrderLineRequest(OIL, 2, null, false)), HttpStatus.CONFLICT, "MSG171");
    }

    @Test
    @DisplayName("UTCID22 N: 1 oil sellable of 2, the customer waits -> order 10 sells 1, waiting order 11 has 1")
    void utc22() {
        sellable.put(OIL, 1);

        SalesOrderDetail order = service.create(manager, walkIn(null, line(OIL, 2)));

        assertThat(order.id()).isEqualTo(10);
        assertThat(order.status()).isEqualTo(SalesOrderStatus.AWAITING_PICKING);
        assertThat(order.lines()).singleElement().satisfies(l -> assertThat(l.requestedQty()).isEqualTo(1));
        assertThat(order.split()).isEqualTo(new SalesOrderDetail.Split(10L, 11L, null, List.of()));
        SalesOrder waiting = saved.get(11L);
        assertThat(waiting.getStatus()).isEqualTo(SalesOrderStatus.AWAITING_STOCK);
        assertThat(linesOf(11)).singleElement().satisfies(l -> assertThat(l.getRequestedQty()).isEqualTo(1));
    }

    @Test
    @DisplayName("UTCID23 N: 1 oil sellable of 2, the customer does not wait -> 1 declined on cancelled order 11")
    void utc23() {
        sellable.put(OIL, 1);

        SalesOrderDetail order = service.create(manager, walkIn(null, new SalesOrderLineRequest(OIL, 2, null,
                false)));

        assertThat(order.id()).isEqualTo(10);
        assertThat(order.status()).isEqualTo(SalesOrderStatus.AWAITING_PICKING);
        assertThat(order.lines()).singleElement().satisfies(l -> assertThat(l.requestedQty()).isEqualTo(1));
        assertThat(order.split()).isEqualTo(new SalesOrderDetail.Split(10L, null, 11L,
                List.of(new SalesOrderDetail.Declined(OIL, 1))));
        assertThat(saved.get(11L).getStatus()).isEqualTo(SalesOrderStatus.CANCELLED);
        assertThat(saved.get(11L).getCancellationReason()).isEqualTo("Khách không chờ hàng");
    }

    @Test
    @DisplayName("UTCID24 B: no oil sellable, the customer waits -> order 10 itself waits for 2")
    void utc24() {
        sellable.put(OIL, 0);

        SalesOrderDetail order = service.create(manager, walkIn(null, line(OIL, 2)));

        assertThat(order.id()).isEqualTo(10);
        assertThat(order.status()).isEqualTo(SalesOrderStatus.AWAITING_STOCK);
        assertThat(order.lines()).singleElement().satisfies(l -> assertThat(l.requestedQty()).isEqualTo(2));
        assertThat(order.split()).isEqualTo(new SalesOrderDetail.Split(null, 10L, null, List.of()));
        assertThat(saved).hasSize(1);
        verify(notifications, never()).notifyStoreManagers(anyLong(), anyString(), anyString(), anyString(),
                anyLong());
    }
}
