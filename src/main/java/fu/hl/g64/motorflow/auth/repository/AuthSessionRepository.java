package fu.hl.g64.motorflow.auth.repository;

import fu.hl.g64.motorflow.auth.entity.AuthSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    @Query("select s from AuthSession s join fetch s.user where s.id = :id")
    Optional<AuthSession> findWithUserById(Long id);

    /** Row-locked so that two parallel refreshes cannot both rotate the same token. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s join fetch s.user where s.refreshTokenHash = :hash")
    Optional<AuthSession> findByRefreshTokenHashForUpdate(String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s join fetch s.user where s.previousRefreshTokenHash = :hash")
    Optional<AuthSession> findByPreviousRefreshTokenHashForUpdate(String hash);

    Optional<AuthSession> findByRefreshTokenHash(String hash);

    /** The newest session of each user who ever signed in. */
    @Query("select s from AuthSession s where s.id in (select max(x.id) from AuthSession x group by x.user.id)")
    List<AuthSession> findLatestPerUser();

    Optional<AuthSession> findFirstByUserIdOrderByIdDesc(Long userId);

    @Query("""
            select s from AuthSession s
            where s.user.id = :userId and s.revokedAt is null and s.expiresAt > :now
            order by s.id desc""")
    List<AuthSession> findActiveByUserId(Long userId, LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AuthSession s set s.revokedAt = :now, s.revokeReason = :reason
            where s.user.id = :userId and s.id <> :keepSessionId and s.revokedAt is null""")
    int revokeOtherSessions(Long userId, Long keepSessionId, LocalDateTime now, AuthSession.RevokeReason reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AuthSession s set s.revokedAt = :now, s.revokeReason = :reason
            where s.user.id = :userId and s.revokedAt is null""")
    int revokeAllSessions(Long userId, LocalDateTime now, AuthSession.RevokeReason reason);
}
