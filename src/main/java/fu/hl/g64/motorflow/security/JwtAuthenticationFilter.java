package fu.hl.g64.motorflow.security;

import fu.hl.g64.motorflow.auth.service.AuthService;
import fu.hl.g64.motorflow.common.error.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Reads "Authorization: Bearer ..." and signs the request in. A missing or bad token leaves the
 * request anonymous; Spring Security then answers 401 for protected URLs, with the reason
 * (MSG06 or MSG10) taken from {@link #AUTH_ERROR_ATTRIBUTE}.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".errorCode";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AuthService authService;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final WebAuthenticationDetailsSource detailsSource = new WebAuthenticationDetailsSource();

    public JwtAuthenticationFilter(JwtService jwtService, AuthService authService) {
        this.jwtService = jwtService;
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            authenticate(header.substring(BEARER_PREFIX.length()).strip(), request);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        Optional<JwtService.AccessTokenClaims> claims = jwtService.parse(token);
        if (claims.isEmpty()) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, "MSG06");
            return;
        }
        try {
            AuthenticatedUser user = authService.authenticate(claims.get().userId(), claims.get().sessionId());
            UsernamePasswordAuthenticationToken authentication =
                    UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities());
            authentication.setDetails(detailsSource.buildDetails(request));
            SecurityContext context = contextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            contextHolder.setContext(context);
        } catch (BusinessException e) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, e.getCode());
        }
    }
}
