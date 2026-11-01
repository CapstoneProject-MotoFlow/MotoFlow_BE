package fu.hl.g64.motorflow.auth.service;

import fu.hl.g64.motorflow.auth.dto.SessionResponse;
import fu.hl.g64.motorflow.auth.entity.AuthSession;
import fu.hl.g64.motorflow.auth.repository.AuthSessionRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/** The devices a user is signed in on, and signing out one of them. */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final AuthSessionRepository sessions;
    private final Clock clock;

    /** Open sessions only, newest sign-in first. */
    @Transactional(readOnly = true)
    public List<SessionResponse> activeSessions(long userId, Long currentSessionId) {
        return sessions.findActiveByUserId(userId, LocalDateTime.now(clock)).stream()
                .map(session -> SessionResponse.from(session, currentSessionId))
                .toList();
    }

    /**
     * Ends one open session of this user; its access token stops working on the next request.
     * Another user's session or one already ended gives MSG75, so session ids of others are not revealed.
     */
    @Transactional
    public void signOut(long userId, long sessionId, AuthSession.RevokeReason reason) {
        LocalDateTime now = LocalDateTime.now(clock);
        AuthSession session = sessions.findById(sessionId)
                .filter(s -> s.getUser().getId() == userId && s.isActive(now))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG75"));
        session.revoke(now, reason);
    }
}
