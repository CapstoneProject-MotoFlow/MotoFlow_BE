package fu.hl.g64.motorflow.inventory.dto;

/** A quantity of one lot: what the caller asks for, or what the posting service actually took. */
public record LotQuantity(Long lotId, int quantity) {
}
