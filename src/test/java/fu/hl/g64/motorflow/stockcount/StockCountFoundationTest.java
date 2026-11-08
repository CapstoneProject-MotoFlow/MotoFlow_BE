package fu.hl.g64.motorflow.stockcount;

import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.entity.InventoryPeriod;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountRequestStatus;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.inventory.helper.InventoryPeriods;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.stockcount.dto.StockCountDetailResponse;
import fu.hl.g64.motorflow.stockcount.helper.StockCountAccess;
import fu.hl.g64.motorflow.stockcount.helper.StockCountAccess.Viewer;
import fu.hl.g64.motorflow.stockcount.helper.StockCountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the stock count workers build on: the role-dependent detail, the permission checks and the period helper. */
class StockCountFoundationTest extends StockCountTestData {

    @Autowired
    private StockCountMapper mapper;
    @Autowired
    private StockCountAccess access;
    @Autowired
    private InventoryPeriods inventoryPeriods;
    @Autowired
    private TransactionTemplate tx;

    @Test
    void theDetailHidesBookQuantitiesFromTheCounterAndValuesFromEveryoneButTheOwner() {
        long request = seedRequest(store, ScopeType.WHOLE, null, StockCountRequestStatus.DONE);
        long count = count(StockCountStatus.AWAITING_APPROVAL).request(request)
                .line(oilLine(12, 10).because("Rơi vỡ").previously(11, 9))
                .line(filterLotALine(4, 4))
                .task(staff, WarehouseTaskStatus.DONE)
                .insert();

        StockCountDetailResponse owner = mapper.toDetail(access.loadCount(count), Viewer.OWNER);
        StockCountDetailResponse manager = mapper.toDetail(access.loadCount(count), Viewer.MANAGER);
        StockCountDetailResponse counter = mapper.toDetail(access.loadCount(count), Viewer.COUNTER);

        // Lines by part: oil (no lot) before filter.
        StockCountDetailResponse.Line ownerOil = owner.lines().getFirst();
        assertThat(ownerOil.productId()).isEqualTo(oil);
        assertThat(ownerOil.lotId()).isNull();
        assertThat(ownerOil.storageArea()).isEqualTo("Kệ A");
        assertThat(ownerOil.bookQty()).isEqualTo(12);
        assertThat(ownerOil.countedQty()).isEqualTo(10);
        assertThat(ownerOil.difference()).isEqualTo(-2);
        assertThat(ownerOil.differenceValue()).isEqualByComparingTo(new BigDecimal("-170000"));
        assertThat(ownerOil.reason()).isEqualTo("Rơi vỡ");
        assertThat(owner.lines().get(1).lotId()).isEqualTo(filterLotA);
        assertThat(owner.lines().get(1).differenceValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(owner.request().id()).isEqualTo(request);
        assertThat(owner.request().reason()).isEqualTo("Kiểm định kỳ");
        assertThat(owner.storeName()).isEqualTo("Gia Lâm");

        // BR-72: a Branch Manager sees quantities and differences but no value.
        StockCountDetailResponse.Line managerOil = manager.lines().getFirst();
        assertThat(managerOil.bookQty()).isEqualTo(12);
        assertThat(managerOil.difference()).isEqualTo(-2);
        assertThat(managerOil.previousBookQty()).isEqualTo(11);
        assertThat(managerOil.differenceValue()).isNull();

        // UC-92: the counter sees what they counted, nothing of the book.
        StockCountDetailResponse.Line counterOil = counter.lines().getFirst();
        assertThat(counterOil.countedQty()).isEqualTo(10);
        assertThat(counterOil.bookQty()).isNull();
        assertThat(counterOil.difference()).isNull();
        assertThat(counterOil.differenceValue()).isNull();
        assertThat(counterOil.reason()).isNull();
        assertThat(counterOil.previousBookQty()).isNull();
        assertThat(counterOil.previousCountedQty()).isNull();
        assertThat(counterOil.storageArea()).isEqualTo("Kệ A");
    }

    @Test
    void aCountWaitingForARestartAfterARecountSaysSo() {
        long count = count(StockCountStatus.COUNTING).awaitingRestart().confirmedOnce()
                .line(oilLine(12, 10).recount("Đếm lại kệ A"))
                .task(staff, WarehouseTaskStatus.ASSIGNED)
                .insert();

        StockCountDetailResponse detail = mapper.toDetail(access.loadCount(count), Viewer.COUNTER);

        assertThat(detail.awaitingBookQuantities()).isTrue();
        assertThat(detail.lines().getFirst().recountRequested()).isTrue();
        assertThat(detail.lines().getFirst().recountReason()).isEqualTo("Đếm lại kệ A");
        assertThat(access.loadCount(count).wasConfirmed()).isTrue();
    }

    @Test
    void viewersAreTheOwnerTheStoresManagersAndTheCounter() {
        long count = count(StockCountStatus.COUNTING).line(oilLine(12, null))
                .task(staff, WarehouseTaskStatus.ASSIGNED).insert();
        var loaded = access.loadCount(count);

        assertThat(access.viewerOf(principal(owner), loaded)).isEqualTo(Viewer.OWNER);
        assertThat(access.viewerOf(principal(manager), loaded)).isEqualTo(Viewer.MANAGER);
        assertThat(access.viewerOf(principal(staff), loaded)).isEqualTo(Viewer.COUNTER);
        assertCode(() -> access.viewerOf(principal(staff2), loaded), "MSG238");
        assertCode(() -> access.viewerOf(principal(otherManager), loaded), "MSG05");
        assertCode(() -> access.requireManagerOf(principal(otherManager), store), "MSG05");
        assertCode(() -> access.requireOwner(principal(manager)), "MSG05");
        assertCode(() -> access.loadCount(count + 99), "MSG245");
        assertCode(() -> access.loadRequest(99), "MSG246");
    }

    @Test
    void onlyTheHolderOfTheOpenTaskRecords() {
        long count = count(StockCountStatus.COUNTING).line(oilLine(12, null))
                .task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();
        var loaded = access.loadCount(count);

        assertThat(access.requireCounter(principal(staff), loaded).getStatus())
                .isEqualTo(WarehouseTaskStatus.RESULT_SUBMITTED);
        assertCode(() -> access.requireCounter(principal(staff2), loaded), "MSG238");

        jdbc.update("UPDATE warehouse_task SET status = 'CANCELLED' WHERE stock_count_id = ?", count);
        assertCode(() -> access.requireCounter(principal(staff), loaded), "MSG238");
        assertThat(access.isCounter(principal(staff), loaded)).isFalse();
    }

    @Test
    void anOpenPeriodIsRequiredForTheCountsPeriod() {
        assertThat(access.requireOpenPeriod(period).getId()).isEqualTo(period);
        closePeriod(period);
        assertCode(() -> access.requireOpenPeriod(period), "MSG232");
        assertCode(() -> access.requireOpenPeriod(null), "MSG232");
    }

    @Test
    void periodsOpenOnFirstUseChainedToTheOneBeforeAndRefuseWhenClosed() {
        LocalDate november = LocalDate.of(2026, 11, 20);
        assertThat(inventoryPeriods.findFor(november)).isEmpty();

        InventoryPeriod opened = tx.execute(status -> inventoryPeriods.openFor(november));

        assertThat(opened.getPeriodMonth()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(opened.getToDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(opened.getPreviousPeriodId()).isEqualTo(period);
        assertThat(tx.execute(status -> inventoryPeriods.openFor(november.withDayOfMonth(2))).getId())
                .isEqualTo(opened.getId());

        closePeriod(period);
        assertThatThrownBy(() -> tx.execute(status -> inventoryPeriods.openFor(TODAY)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("MSG85");
                    assertThat(e.getArgs()).containsExactly("10/2026");
                });
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }
}
