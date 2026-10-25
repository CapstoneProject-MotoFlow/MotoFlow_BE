package fu.hl.g64.motorflow.account.dto;

/** UC-11. Returned once, like {@link CreatedAccountResponse#temporaryPassword()}. */
public record TemporaryPasswordResponse(String temporaryPassword) {
}
