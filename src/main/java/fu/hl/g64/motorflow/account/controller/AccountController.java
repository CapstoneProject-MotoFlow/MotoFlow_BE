package fu.hl.g64.motorflow.account.controller;

import fu.hl.g64.motorflow.account.dto.AccountResponse;
import fu.hl.g64.motorflow.account.dto.AccountState;
import fu.hl.g64.motorflow.account.dto.CreateAccountRequest;
import fu.hl.g64.motorflow.account.dto.CreatedAccountResponse;
import fu.hl.g64.motorflow.account.dto.LockAccountRequest;
import fu.hl.g64.motorflow.account.dto.PermissionHistoryResponse;
import fu.hl.g64.motorflow.account.dto.ResetPasswordRequest;
import fu.hl.g64.motorflow.account.dto.TemporaryPasswordResponse;
import fu.hl.g64.motorflow.account.dto.UpdatePermissionsRequest;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.service.AccountService;
import fu.hl.g64.motorflow.account.service.PermissionService;
import fu.hl.g64.motorflow.auth.dto.SessionResponse;
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
@RequestMapping("/api/v1/accounts")
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

    @Operation(summary = "One account with its store and permissions")
    @GetMapping("/{id}")
    public AccountResponse detail(@PathVariable long id) {
        return accountService.getAccount(id);
    }

    @Operation(summary = "Create an account; the generated temporary password is returned only here (UC-08)")
    @PostMapping
    public ResponseEntity<CreatedAccountResponse> create(@AuthenticationPrincipal AuthenticatedUser admin,
                                                         @Valid @RequestBody CreateAccountRequest request) {
        CreatedAccountResponse created = accountService.createAccount(admin, request);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + created.account().id())).body(created);
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

    @Operation(summary = "Issue a new generated temporary password, returned only here (UC-11)")
    @PostMapping("/{id}/reset-password")
    public TemporaryPasswordResponse resetPassword(@AuthenticationPrincipal AuthenticatedUser admin,
                                                   @PathVariable long id,
                                                   @Valid @RequestBody(required = false) ResetPasswordRequest request) {
        return accountService.resetPassword(admin, id, request == null ? null : request.reason());
    }

    @Operation(summary = "Devices the account is signed in on, newest first")
    @GetMapping("/{id}/sessions")
    public List<SessionResponse> sessions(@PathVariable long id) {
        return accountService.activeSessions(id);
    }

    @Operation(summary = "Sign the account out of one device")
    @PostMapping("/{id}/sessions/{sessionId}/sign-out")
    public ResponseEntity<Void> signOutSession(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable long id,
                                               @PathVariable long sessionId,
                                               @Valid @RequestBody(required = false) LockAccountRequest request) {
        accountService.signOutSession(admin, id, sessionId, request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Role, store and permission changes of one account, newest first (UC-12)")
    @GetMapping("/{id}/permission-history")
    public List<PermissionHistoryResponse> permissionHistory(@PathVariable long id) {
        return permissionService.permissionHistory(id);
    }
}
