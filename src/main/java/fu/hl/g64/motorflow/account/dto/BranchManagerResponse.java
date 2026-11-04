package fu.hl.g64.motorflow.account.dto;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;

/** UC-38: a Branch Manager with the permissions the Owner can grant. */
public record BranchManagerResponse(long id, String username, String fullName, Long storeId, String storeName,
                                    AccountStatus status, boolean catalogEdit, boolean retailPriceEdit) {

    public static BranchManagerResponse from(UserAccount manager, String storeName) {
        return new BranchManagerResponse(manager.getId(), manager.getUsername(), manager.getFullName(),
                manager.getStoreId(), storeName, manager.getStatus(), manager.isCanEditCatalog(),
                manager.isCanEditRetailPrice());
    }
}
