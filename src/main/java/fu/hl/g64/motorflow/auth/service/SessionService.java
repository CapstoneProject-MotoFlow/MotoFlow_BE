package fu.hl.g64.motorflow.auth.service;

import fu.hl.g64.motorflow.auth.dto.SessionResponse;
import fu.hl.g64.motorflow.auth.entity.AuthSession;

import java.util.List;

/** The devices a user is signed in on, and signing out one of them or all but the current one. */
public interface SessionService {
    /** Open sessions only, newest sign-in first. */
    List<SessionResponse> activeSessions(long userId, Long currentSessionId);

    /**
     * Ends one open session of this user; its access token stops working on the next request.
     * Another user's session or one already ended gives MSG75, so session ids of others are not revealed.
     */
    void signOut(long userId, long sessionId, AuthSession.RevokeReason reason);

    /**
     * Ends every other open session of this user in one update, keeping {@code currentSessionId}; their access tokens
     * stop working on the next request. Nothing to end is not an error.
     */
    void signOutOthers(long userId, long currentSessionId);
}
