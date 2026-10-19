package fu.hl.g64.motorflow.account.controller;

import fu.hl.g64.motorflow.account.dto.AccountResponse;
import fu.hl.g64.motorflow.account.dto.AccountState;
import fu.hl.g64.motorflow.account.dto.CreateAccountRequest;
import fu.hl.g64.motorflow.account.dto.LockAccountRequest;
import fu.hl.g64.motorflow.account.dto.PermissionHistoryResponse;
import fu.hl.g64.motorflow.account.dto.ResetPasswordRequest;
import fu.hl.g64.motorflow.account.dto.UpdatePermissionsRequest;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.service.AccountService;
import fu.hl.g64.motorflow.account.service.PermissionService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@Tag(name = "Accounts", description = "UC-07 to UC-12: accounts, permissions and their history (Admin)")
@RestController
@RequestMapping("/api/accounts")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final PermissionService permissionService;

    @Operation(summary = "List accounts, most recent sign-in first (UC-07)")
    @GetMapping
    public List<AccountResponse> list(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) Role role,
                                      @RequestParam(required = false) Long storeId,
                                      @RequestParam(required = false) AccountState state) {
        return accountService.listAccounts(q, role, storeId, state);
    }

    @Operation(summary = "Create an account with a temporary password (UC-08)")
    @PostMapping
    public ResponseEntity<AccountResponse> create(@AuthenticationPrincipal AuthenticatedUser admin,
                                                  @Valid @RequestBody CreateAccountRequest request) {
        AccountResponse created = accountService.createAccount(admin, request);
        return ResponseEntity.created(URI.create("/api/accounts/" + created.id())).body(created);
    }

    @Operation(summary = "Change role, store and catalog permission (UC-09); 409 MSG18 asks to release assigned tasks")
    @PutMapping("/{id}/permissions")
    public AccountResponse updatePermissions(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable long id,
                                             @Valid @RequestBody UpdatePermissionsRequest request) {
        return permissionService.updatePermissions(admin, id, request);
    }

    @Operation(summary = "Lock an account and sign it out everywhere (UC-10)")
    @PostMapping("/{id}/lock")
    public ResponseEntity<Void> lock(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable long id,
                                     @Valid @RequestBody(required = false) LockAccountRequest request) {
        accountService.lockAccount(admin, id, request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Unlock an account, also from a temporary lock (UC-10)")
    @PostMapping("/{id}/unlock")
    public ResponseEntity<Void> unlock(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable long id,
                                       @Valid @RequestBody(required = false) LockAccountRequest request) {
        accountService.unlockAccount(admin, id, request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Issue a new temporary password (UC-11)")
    @PostMapping("/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(@AuthenticationPrincipal AuthenticatedUser admin,
                                              @PathVariable long id,
                                              @Valid @RequestBody ResetPasswordRequest request) {
        accountService.resetPassword(admin, id, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Role, store and permission changes of one account, newest first (UC-12)")
    @GetMapping("/{id}/permission-history")
    public List<PermissionHistoryResponse> permissionHistory(@PathVariable long id) {
        return permissionService.permissionHistory(id);
    }
}
