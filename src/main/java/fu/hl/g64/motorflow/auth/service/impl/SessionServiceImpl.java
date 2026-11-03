package fu.hl.g64.motorflow.auth.service.impl;

import fu.hl.g64.motorflow.auth.dto.SessionResponse;
import fu.hl.g64.motorflow.auth.entity.AuthSession;
import fu.hl.g64.motorflow.auth.repository.AuthSessionRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.auth.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SessionServiceImpl implements SessionService {

    private final AuthSessionRepository sessions;
    private final Clock clock;

    @Transactional(readOnly = true)
    @Override
    public List<SessionResponse> activeSessions(long userId, Long currentSessionId) {
        return sessions.findActiveByUserId(userId, LocalDateTime.now(clock)).stream()
                .map(session -> SessionResponse.from(session, currentSessionId))
                .toList();
    }

    @Transactional
    @Override
    public void signOut(long userId, long sessionId, AuthSession.RevokeReason reason) {
        LocalDateTime now = LocalDateTime.now(clock);
        AuthSession session = sessions.findById(sessionId)
                .filter(s -> s.getUser().getId() == userId && s.isActive(now))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG75"));
        session.revoke(now, reason);
    }
}
