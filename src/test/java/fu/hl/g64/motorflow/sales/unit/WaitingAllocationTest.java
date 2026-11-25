package fu.hl.g64.motorflow.sales.unit;

import fu.hl.g64.motorflow.sales.helper.WaitingAllocation;
import fu.hl.g64.motorflow.sales.helper.WaitingAllocation.Need;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GVHD 24/11 (BR-90): stock is shared out among waiting orders oldest first. */
class WaitingAllocationTest {

    private static final long STORE = 1;
    private static final long PAD = 10;
    private static final long OIL = 11;

    @Test
    void fourUnitsForThreeOrdersOfFourMakeOnlyTheOldestReady() {
        List<Need> needs = List.of(new Need(1, STORE, PAD, 4), new Need(2, STORE, PAD, 4), new Need(3, STORE, PAD, 4));
        int[] ready = WaitingAllocation.allocate(needs, Map.of(STORE, Map.of(PAD, 4)));
        assertThat(ready).containsExactly(4, 0, 0);
        assertThat(WaitingAllocation.readyOrders(needs, ready)).containsExactly(1L);
    }

    @Test
    void anOlderOrderKeepsItsClaimEvenWhenNotFullyCovered() {
        List<Need> needs = List.of(new Need(1, STORE, PAD, 5), new Need(2, STORE, PAD, 2));
        int[] ready = WaitingAllocation.allocate(needs, Map.of(STORE, Map.of(PAD, 4)));
        assertThat(ready).containsExactly(4, 0);
        assertThat(WaitingAllocation.readyOrders(needs, ready)).isEmpty();
    }

    @Test
    void anOrderIsReadyOnlyWhenEveryLineIsCovered() {
        List<Need> needs = List.of(new Need(1, STORE, PAD, 2), new Need(1, STORE, OIL, 1), new Need(2, STORE, PAD, 1));
        int[] ready = WaitingAllocation.allocate(needs, Map.of(STORE, Map.of(PAD, 3)));
        assertThat(ready).containsExactly(2, 0, 1);
        assertThat(WaitingAllocation.readyOrders(needs, ready)).containsExactly(2L);
    }

    @Test
    void storesDoNotShareStock() {
        List<Need> needs = List.of(new Need(1, STORE, PAD, 2), new Need(2, 2, PAD, 2));
        int[] ready = WaitingAllocation.allocate(needs, Map.of(STORE, Map.of(PAD, 4)));
        assertThat(ready).containsExactly(2, 0);
    }
}
