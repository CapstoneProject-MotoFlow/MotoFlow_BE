package fu.hl.g64.motorflow.transfer;

import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DispatchTransferOrderUnitTest {

    @Test
    void dispatchStoresWhoCarriedTheGoods() {
        TransferOrder order = TransferOrder.create(null, 1L, 2L, 3L, LocalDateTime.of(2026, 10, 1, 9, 0));
        LocalDateTime when = LocalDateTime.of(2026, 10, 1, 10, 0);
        order.dispatch(3L, "  Anh Hùng  ", "29C-678.90", when);

        assertThat(order.getStatus()).isEqualTo(TransferOrderStatus.IN_TRANSIT);
        assertThat(order.getDispatchedBy()).isEqualTo(3L);
        assertThat(order.getDispatchedAt()).isEqualTo(when);
        assertThat(order.getDelivererName()).isEqualTo("  Anh Hùng  ");
        assertThat(order.getVehiclePlateNo()).isEqualTo("29C-678.90");
    }

    @Test
    void dispatchRefusesWhenNotAwaitingDispatch() {
        TransferOrder order = TransferOrder.create(null, 1L, 2L, 3L, LocalDateTime.of(2026, 10, 1, 9, 0));
        order.dispatch(3L, null, null, LocalDateTime.of(2026, 10, 1, 10, 0));

        assertThatThrownBy(() -> order.dispatch(3L, null, null, LocalDateTime.of(2026, 10, 1, 11, 0)))
                .isInstanceOf(IllegalStateException.class);
    }
}