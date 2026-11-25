package fu.hl.g64.motorflow.sales.entity.enums;

/**
 * GVHD 24/11: where a waiting order stands in "Đơn chưa đáp ứng đủ" (UC-59). WAITING and READY are both
 * AWAITING_STOCK; READY means the store's sellable stock, shared out oldest order first, covers every line now.
 * RELEASED went to picking (in full, or the part a release sold); CANCELLED was cancelled or lost its deposit.
 */
public enum WaitingOrderState {
    WAITING, READY, RELEASED, CANCELLED
}
