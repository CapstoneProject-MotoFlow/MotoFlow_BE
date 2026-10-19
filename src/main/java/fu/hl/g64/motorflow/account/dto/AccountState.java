package fu.hl.g64.motorflow.account.dto;

/**
 * What SCR-05 shows: "Hoạt động", "Tạm khóa" (wrong passwords, lifts by itself) or "Ngừng hoạt động" (locked by Admin).
 * An Admin lock wins over a temporary lock.
 */
public enum AccountState {
    ACTIVE,
    TEMPORARILY_LOCKED,
    LOCKED
}
