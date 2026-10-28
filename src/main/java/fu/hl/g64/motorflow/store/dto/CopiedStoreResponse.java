package fu.hl.g64.motorflow.store.dto;

/** UC-05: the new store, and for how many parts it took the template's storage location and low-stock threshold. */
public record CopiedStoreResponse(StoreResponse store, int copiedPartSettings) {
}
