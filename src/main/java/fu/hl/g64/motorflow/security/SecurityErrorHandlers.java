package fu.hl.g64.motorflow.security;

import fu.hl.g64.motorflow.common.error.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Turns Spring Security's 401 and 403 into the same JSON body as every other error, by handing
 * them to GlobalExceptionHandler.
 */
@Component
public class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    public SecurityErrorHandlers(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    /** No valid sign-in: expired or revoked session (MSG06), or an account locked by Admin (MSG10). */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) {
        Object code = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
        resolve(request, response, new BusinessException(HttpStatus.UNAUTHORIZED, code != null ? code.toString() : "MSG06"));
    }

    /** Signed in but not allowed: still on a temporary password (MSG13), or outside the role (MSG05). */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean mustChangePassword = authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser user
                && user.mustChangePassword();
        resolve(request, response, new BusinessException(HttpStatus.FORBIDDEN, mustChangePassword ? "MSG13" : "MSG05"));
    }

    private void resolve(HttpServletRequest request, HttpServletResponse response, BusinessException ex) {
        resolver.resolveException(request, response, null, ex);
    }
}
