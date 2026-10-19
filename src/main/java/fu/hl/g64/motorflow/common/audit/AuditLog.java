package fu.hl.g64.motorflow.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Who did what to which record, and when (NFR-S06). Rows are never updated or deleted.
 * The JSONB columns old_data / new_data are not mapped yet; map them when a feature needs
 * before/after values.
 */
@Entity
@Table(name = "audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false, updatable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private Long entityId;

    @Column(nullable = false, updatable = false, length = 50)
    private String action;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(updatable = false, length = 500)
    private String reason;

    public static AuditLog record(String entityType, Long entityId, String action, Long actorId,
                                  String reason, LocalDateTime now) {
        AuditLog entry = new AuditLog();
        entry.entityType = entityType;
        entry.entityId = entityId;
        entry.action = action;
        entry.actorId = actorId;
        entry.reason = reason;
        entry.occurredAt = now;
        return entry;
    }
}
