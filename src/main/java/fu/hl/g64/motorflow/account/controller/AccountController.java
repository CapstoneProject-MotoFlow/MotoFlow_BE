package fu.hl.g64.motorflow.account.controller;

import fu.hl.g64.motorflow.account.dto.AccountResponse;
import fu.hl.g64.motorflow.account.dto.CreateAccountRequest;
import fu.hl.g64.motorflow.account.dto.ResetPasswordRequest;
import fu.hl.g64.motorflow.account.service.AccountService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = "Accounts", description = "UC-08 Create Account, UC-11 Reset Password (Admin)")
@RestController
@RequestMapping("/api/accounts")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @Operation(summary = "Create an account with a temporary password (UC-08)")
    @PostMapping
    public ResponseEntity<AccountResponse> create(@AuthenticationPrincipal AuthenticatedUser admin,
                                                  @Valid @RequestBody CreateAccountRequest request) {
        AccountResponse created = accountService.createAccount(admin, request);
        return ResponseEntity.created(URI.create("/api/accounts/" + created.id())).body(created);
    }

    @Operation(summary = "Issue a new temporary password (UC-11)")
    @PostMapping("/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(@AuthenticationPrincipal AuthenticatedUser admin,
                                              @PathVariable long id,
                                              @Valid @RequestBody ResetPasswordRequest request) {
        accountService.resetPassword(admin, id, request);
        return ResponseEntity.noContent().build();
    }
}
