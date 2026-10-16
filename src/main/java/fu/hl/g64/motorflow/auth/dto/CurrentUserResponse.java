package fu.hl.g64.motorflow.auth.dto;

import fu.hl.g64.motorflow.account.entity.enums.PermissionCode;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.util.Set;

/**
 * What the frontend needs to build the menu for the user's role (UC-01 step 8).
 * When {@code mustChangePassword} is true it must open Change Password first (MSG13).
 */
public record CurrentUserResponse(long id, String username, String fullName, Role role, Long storeId,
                                  Set<PermissionCode> permissions, boolean mustChangePassword) {

    public static CurrentUserResponse from(AuthenticatedUser user) {
        return new CurrentUserResponse(user.id(), user.username(), user.fullName(), user.role(), user.storeId(),
                user.permissions(), user.mustChangePassword());
    }
}
