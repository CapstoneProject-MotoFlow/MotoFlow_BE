package fu.hl.g64.motorflow.security;

import fu.hl.g64.motorflow.account.entity.enums.PermissionCode;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The signed-in user of the current request, read from the database on every request so that
 * a role, store or permission change applies from the user's next action (BR-03).
 * Get it in a controller with {@code @AuthenticationPrincipal AuthenticatedUser user}.
 */
public record AuthenticatedUser(long id, long sessionId, String username, String fullName, Role role,
                                Long storeId, Set<PermissionCode> permissions, boolean mustChangePassword) {

    /** ROLE_OWNER, ROLE_BRANCH_MANAGER, ... and PERM_CATALOG_EDIT, PERM_RETAIL_PRICE_EDIT. */
    public List<GrantedAuthority> authorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        permissions.forEach(code -> authorities.add(new SimpleGrantedAuthority("PERM_" + code.name())));
        return authorities;
    }
}
