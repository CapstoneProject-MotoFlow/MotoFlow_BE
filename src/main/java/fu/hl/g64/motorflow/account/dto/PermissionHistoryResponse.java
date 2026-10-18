package fu.hl.g64.motorflow.account.dto;

import fu.hl.g64.motorflow.account.entity.PermissionHistory;

import java.time.LocalDateTime;

/** UC-12. Old and new values are role names, store codes or empty for a permission grant or revoke. */
public record PermissionHistoryResponse(long id, String changeType, String permissionCode, String oldValue,
                                        String newValue, String reason, long changedBy, String changedByName,
                                        LocalDateTime changedAt) {

    public static PermissionHistoryResponse from(PermissionHistory entry, String changedByName) {
        return new PermissionHistoryResponse(entry.getId(), entry.getChangeType(), entry.getPermissionCode(),
                entry.getOldValue(), entry.getNewValue(), entry.getReason(), entry.getChangedBy(), changedByName,
                entry.getChangedAt());
    }
}
