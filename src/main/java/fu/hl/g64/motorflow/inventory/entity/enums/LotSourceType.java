package fu.hl.g64.motorflow.inventory.entity.enums;

/** Values of lot.source_type (CHECK in the schema). */
public enum LotSourceType {
    RECEIPT,
    OPENING,
    COUNT_SURPLUS,
    /** GVHD 20/11 (PR 5, V15): goods of a supplier discrepancy taken as another part; it has the PO's supplier. */
    DISCREPANCY
}
