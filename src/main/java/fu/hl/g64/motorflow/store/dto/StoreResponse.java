package fu.hl.g64.motorflow.store.dto;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A row of SCR-06. {@code central} marks the store holding the chain's central warehouse (BR-04);
 * {@code managers} lists the active Owner managing the store (if any) and its active Branch Managers,
 * and is empty when the store has none ("Chưa gán"); {@code ownerManagerId} is that Owner, active or not;
 * {@code copiedFromStoreId} is the template store when the store was created by copying one (UC-05).
 */
public record StoreResponse(long id, String storeName, String address, String phone,
                            StoreStatus status, boolean central, List<ManagerInfo> managers, Long ownerManagerId,
                            long productsInStock, Long copiedFromStoreId,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {

    public record ManagerInfo(long id, String fullName, String phone, Role role) {
    }

    public static StoreResponse from(Store store, List<UserAccount> managers, long productsInStock) {
        return new StoreResponse(store.getId(), store.getStoreName(), store.getAddress(),
                store.getPhone(), store.getStatus(), store.isCentral(),
                managers.stream().map(m -> new ManagerInfo(m.getId(), m.getFullName(), m.getPhone(), m.getRole())).toList(),
                store.getOwnerManagerId(),
                productsInStock, store.getCopiedFromStoreId(), store.getCreatedAt(), store.getUpdatedAt());
    }
}
