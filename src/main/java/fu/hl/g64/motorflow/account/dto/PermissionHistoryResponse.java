package fu.hl.g64.motorflow.account.dto;

import fu.hl.g64.motorflow.common.audit.AuditLog;

import java.time.LocalDateTime;
import java.util.Map;

/** UC-12, read from audit_log. Old and new values are role names, store ids or empty for a permission grant or revoke. */
public record PermissionHistoryResponse(long id, String changeType, String permissionCode, String oldValue,
                                        String newValue, String reason, long changedBy, String changedByName,
                                        LocalDateTime changedAt) {

    public static PermissionHistoryResponse from(AuditLog entry, String changedByName) {
        Map<String, Object> before = entry.getOldData() == null ? Map.of() : entry.getOldData();
        Map<String, Object> after = entry.getNewData() == null ? Map.of() : entry.getNewData();
        Object code = after.getOrDefault("permission_code", before.get("permission_code"));
        return new PermissionHistoryResponse(entry.getId(), entry.getAction(), code == null ? null : code.toString(),
                text(before.get("value")), text(after.get("value")), entry.getReason(), entry.getActorId(),
                changedByName, entry.getOccurredAt());
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
