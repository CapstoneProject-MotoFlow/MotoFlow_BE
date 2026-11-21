package fu.hl.g64.motorflow.discrepancy.entity.enums;

/**
 * Góp ý UI 21/11: the kind of held goods a return to the supplier hands back (discrepancy_resolution_event.goods_kind),
 * in the order a return without a kind takes them.
 */
public enum GoodsKind {
    REJECTED_EXCESS,
    WRONG_ITEM,
    DAMAGED
}
