package fu.hl.g64.motorflow.store.dto;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;

/** One person of a store on the Owner's store list (UC-03): its Branch Managers, Staff and the Owner managing it. */
public record StoreEmployeeResponse(long id, String fullName, Role role, String phone, AccountStatus status) {

    public static StoreEmployeeResponse from(UserAccount user) {
        return new StoreEmployeeResponse(user.getId(), user.getFullName(), user.getRole(), user.getPhone(), user.getStatus());
    }
}
