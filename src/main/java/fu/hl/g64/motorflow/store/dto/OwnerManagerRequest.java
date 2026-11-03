package fu.hl.g64.motorflow.store.dto;

/** {@code ownerId} is an active Owner account, or null to stop the Owner managing the store. */
public record OwnerManagerRequest(Long ownerId) {
}
