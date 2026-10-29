package fu.hl.g64.motorflow.store.dto;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A row of SCR-06. {@code central} marks the store holding the chain's central warehouse (BR-04);
 * {@code managers} is empty when the store has no active Branch Manager ("Chưa gán");
 * {@code copiedFromStoreId} is the template store when the store was created by copying one (UC-05).
 */
public record StoreResponse(long id, String storeName, String address, String phone,
                            StoreStatus status, boolean central, List<ManagerInfo> managers,
                            long productsInStock, Long copiedFromStoreId,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {

    public record ManagerInfo(long id, String fullName, String phone) {
    }

    public static StoreResponse from(Store store, List<UserAccount> managers, long productsInStock) {
        return new StoreResponse(store.getId(), store.getStoreName(), store.getAddress(),
                store.getPhone(), store.getStatus(), store.isCentral(),
                managers.stream().map(m -> new ManagerInfo(m.getId(), m.getFullName(), m.getPhone())).toList(),
                productsInStock, store.getCopiedFromStoreId(), store.getCreatedAt(), store.getUpdatedAt());
    }
}
