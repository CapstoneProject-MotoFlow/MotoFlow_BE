package fu.hl.g64.motorflow.auth.controller;

import fu.hl.g64.motorflow.auth.dto.AuthResponse;
import fu.hl.g64.motorflow.auth.dto.ChangePasswordRequest;
import fu.hl.g64.motorflow.auth.dto.CurrentUserResponse;
import fu.hl.g64.motorflow.auth.dto.LoginRequest;
import fu.hl.g64.motorflow.auth.service.AuthService;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "UC-01 Log In, UC-02 Change Password")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookies refreshTokenCookies;

    @Operation(summary = "Sign in with username and password (UC-01)")
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request, HttpServletRequest http) {
        AuthService.IssuedTokens tokens = authService.login(request, http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr());
        return withRefreshCookie(tokens);
    }

    @Operation(summary = "Get a new access token using the refresh cookie")
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest http) {
        String refreshToken = refreshTokenCookies.read(http)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "MSG06"));
        return withRefreshCookie(authService.refresh(refreshToken));
    }

    @Operation(summary = "Sign out of this device")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest http, @AuthenticationPrincipal AuthenticatedUser user) {
        authService.logout(refreshTokenCookies.read(http).orElse(null), user == null ? null : user.sessionId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.clear().toString())
                .build();
    }

    @Operation(summary = "The signed-in user with role, store and permissions")
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return CurrentUserResponse.from(user);
    }

    @Operation(summary = "Change own password, including a temporary one (UC-02)")
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AuthenticatedUser user,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(user, request);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<AuthResponse> withRefreshCookie(AuthService.IssuedTokens tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.issue(tokens.refreshToken()).toString())
                .body(tokens.body());
    }
}
