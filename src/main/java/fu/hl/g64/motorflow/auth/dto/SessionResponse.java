package fu.hl.g64.motorflow.auth.dto;

import fu.hl.g64.motorflow.auth.entity.AuthSession;
import fu.hl.g64.motorflow.auth.service.UserAgents;

import java.time.LocalDateTime;

/** One device the user is signed in on. {@code current} marks the device making this request. */
public record SessionResponse(long id, LocalDateTime signedInAt, LocalDateTime lastRefreshedAt,
                              String ipAddress, String device, boolean current) {

    public static SessionResponse from(AuthSession session, Long currentSessionId) {
        return new SessionResponse(session.getId(), session.getCreatedAt(), session.getLastRefreshedAt(),
                session.getIpAddress(), UserAgents.describe(session.getUserAgent()),
                session.getId().equals(currentSessionId));
    }
}
