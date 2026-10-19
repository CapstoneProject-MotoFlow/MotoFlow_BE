package fu.hl.g64.motorflow.account.entity.enums;

/** BR-02: each user holds exactly one role. */
public enum Role {
    ADMIN,
    OWNER,
    BRANCH_MANAGER,
    STAFF;

    /** Branch Managers and Staff work at exactly one store; Owner and Admin at none. */
    public boolean requiresStore() {
        return this == BRANCH_MANAGER || this == STAFF;
    }
}
