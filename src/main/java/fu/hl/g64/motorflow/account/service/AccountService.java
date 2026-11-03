package fu.hl.g64.motorflow.account.service;

import fu.hl.g64.motorflow.account.dto.AccountResponse;
import fu.hl.g64.motorflow.account.dto.AccountState;
import fu.hl.g64.motorflow.account.dto.CreateAccountRequest;
import fu.hl.g64.motorflow.account.dto.CreatedAccountResponse;
import fu.hl.g64.motorflow.account.dto.TemporaryPasswordResponse;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.auth.dto.SessionResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.util.List;

/** UC-07 View Account List, UC-08 Create Account, UC-10 Lock/Unlock Account, UC-11 Reset Password. Admin only (BR-01). */
public interface AccountService {
    /**
     * Every filter is optional; {@code q} matches full name, username, email or store name.
     * Most recent sign-in first; accounts that never signed in come last.
     * shortcut: filters in memory, fine for a chain's few hundred accounts; move to a query if they reach thousands.
     */
    public List<AccountResponse> listAccounts(String q, Role role, Long storeId, AccountState state);

    public AccountResponse getAccount(long accountId);

    public CreatedAccountResponse createAccount(AuthenticatedUser admin, CreateAccountRequest request);

    /** UC-10. The user is signed out on every device at once. Locking a locked account changes nothing. */
    public void lockAccount(AuthenticatedUser admin, long accountId, String reason);

    public List<SessionResponse> activeSessions(long accountId);

    /**
     * Signs the user out of one device, e.g. a lost phone, without locking the account.
     * The session ends as LOGOUT; the audit log records that Admin did it.
     */
    public void signOutSession(AuthenticatedUser admin, long accountId, long sessionId, String reason);

    /** UC-10. Lifts an Admin lock and the temporary lock after wrong passwords alike. */
    public void unlockAccount(AuthenticatedUser admin, long accountId, String reason);

    /**
     * The user must change the new temporary password at the next sign-in, and every device they
     * are signed in on is signed out, because whoever knew the old password must lose access.
     */
    public TemporaryPasswordResponse resetPassword(AuthenticatedUser admin, long accountId, String reason);
}
