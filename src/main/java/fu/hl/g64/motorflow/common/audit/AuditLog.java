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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Who did what to which record, and when (NFR-S09). Rows are never updated or deleted.
 * old_data / new_data hold before/after values where a feature needs them (permission and credit history).
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_data", updatable = false)
    private Map<String, Object> oldData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_data", updatable = false)
    private Map<String, Object> newData;

    public static AuditLog record(String entityType, Long entityId, String action, Long actorId,
                                  String reason, LocalDateTime now) {
        return record(entityType, entityId, action, actorId, reason, null, null, now);
    }

    public static AuditLog record(String entityType, Long entityId, String action, Long actorId, String reason,
                                  Map<String, Object> oldData, Map<String, Object> newData, LocalDateTime now) {
        AuditLog entry = new AuditLog();
        entry.oldData = oldData;
        entry.newData = newData;
        entry.entityType = entityType;
        entry.entityId = entityId;
        entry.action = action;
        entry.actorId = actorId;
        entry.reason = reason;
        entry.occurredAt = now;
        return entry;
    }
}
