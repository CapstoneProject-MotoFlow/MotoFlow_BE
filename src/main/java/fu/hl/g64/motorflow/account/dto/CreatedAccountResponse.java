package fu.hl.g64.motorflow.account.dto;

/**
 * UC-08. The only time the temporary password leaves the system: Admin hands it to the employee,
 * who must change it at the first sign-in. Only its hash is stored, so it cannot be shown again (NFR-S04).
 */
public record CreatedAccountResponse(AccountResponse account, String temporaryPassword) {
}
