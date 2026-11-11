package fu.hl.g64.motorflow.auth.service;

import fu.hl.g64.motorflow.auth.dto.AuthResponse;
import fu.hl.g64.motorflow.auth.dto.ChangePasswordRequest;
import fu.hl.g64.motorflow.auth.dto.CurrentUserResponse;
import fu.hl.g64.motorflow.auth.dto.LoginRequest;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/** UC-01 Log In and UC-02 Change Password, plus the session lifecycle behind them. */
public interface AuthService {
    /** Tokens to hand to the client: the body for JSON, the refresh token for the cookie. */
    record IssuedTokens(AuthResponse body, String refreshToken) {
    }

    /**
     * UC-01. Failed attempts are committed even though the method throws, hence noRollbackFor.
     */
    IssuedTokens login(LoginRequest request, String userAgent, String ipAddress);

    /** Gives a new access token and rotates the refresh token. */
    IssuedTokens refresh(String refreshToken);

    /**
     * Ends the session found by the refresh cookie or by the access token, whichever the client
     * still has. Always succeeds, so logging out twice is harmless.
     */
    void logout(String refreshToken, Long sessionId);

    /**
     * Called for every request that carries an access token: the session must still be open and
     * the account not locked by Admin.
     */
    AuthenticatedUser authenticate(long userId, long sessionId);

    /**
     * UC-02. Other devices are signed out, because a password change usually means the old one
     * was temporary or known to someone else.
     */
    void changePassword(AuthenticatedUser principal, ChangePasswordRequest request);

    /**
     * The signed-in user as the frontend sees it, including whether this is the Branch Manager of the
     * store holding the central warehouse (BR-03/BR-04).
     */
    CurrentUserResponse currentUser(AuthenticatedUser user);
}
