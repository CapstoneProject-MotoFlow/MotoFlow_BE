package fu.hl.g64.motorflow.store.service;

import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.dto.CopiedStoreResponse;
import fu.hl.g64.motorflow.store.dto.CopyStoreRequest;
import fu.hl.g64.motorflow.store.dto.CreateStoreRequest;
import fu.hl.g64.motorflow.store.dto.OwnerManagerRequest;
import fu.hl.g64.motorflow.store.dto.StoreEmployeeResponse;
import fu.hl.g64.motorflow.store.dto.StoreResponse;
import fu.hl.g64.motorflow.store.dto.UpdateStoreRequest;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;

import java.util.List;

/**
 * UC-03 View Store List (with the people of each store), UC-04 Create Store, UC-05 Copy Store Configuration,
 * UC-06 Update Store (including opening and closing a store, and the Owner managing a store).
 */
public interface StoreService {
    /**
     * Every filter is optional. {@code q} matches store id, name, address or Branch Manager name.
     * shortcut: filters in memory, fine for a chain of a few dozen stores; move to a query if stores reach hundreds.
     */
    List<StoreResponse> listStores(String q, StoreStatus status, Boolean central);

    StoreResponse getStore(long storeId);

    /**
     * UC-03: everyone working at the store, any status: the Owner managing it, its Branch Managers, then Staff,
     * each group by name.
     */
    List<StoreEmployeeResponse> listEmployees(long storeId);

    /**
     * The Owner may also manage a store (review 31/10), which counts as the manager a store needs to open.
     * A closed store takes no manager.
     */
    StoreResponse setOwnerManager(AuthenticatedUser actor, long storeId, OwnerManagerRequest request);

    /** A new store is planned and has no stock (BR-04, BR-64). */
    StoreResponse createStore(AuthenticatedUser actor, CreateStoreRequest request);

    /**
     * UC-05: a planned store that stores parts where the template does, with the same low-stock
     * thresholds. Stock, documents and debts are never copied (BR-64), and the copy is never the central warehouse.
     */
    CopiedStoreResponse copyStore(AuthenticatedUser actor, long templateId, CopyStoreRequest request);

    StoreResponse updateStore(AuthenticatedUser actor, long storeId, UpdateStoreRequest request);
}
