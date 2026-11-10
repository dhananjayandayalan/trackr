package dev.trakr.api.identity.web;

import dev.trakr.api.identity.IdentityService;
import dev.trakr.api.identity.UserView;
import dev.trakr.api.identity.internal.LoginAttempts;
import dev.trakr.api.identity.internal.PasswordResetService;
import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.workspace.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Sign up, sign in and password reset. Sign out is the logout filter in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private static final Duration REMEMBER_ME = Duration.ofDays(30);

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy securityContextHolderStrategy = SecurityContextHolder.getContextHolderStrategy();
    private final IdentityService identityService;
    private final WorkspaceService workspaceService;
    private final LoginAttempts loginAttempts;
    private final PasswordResetService passwordResetService;

    AuthController(AuthenticationManager authenticationManager, SecurityContextRepository securityContextRepository,
            IdentityService identityService, WorkspaceService workspaceService, LoginAttempts loginAttempts,
            PasswordResetService passwordResetService) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.identityService = identityService;
        this.workspaceService = workspaceService;
        this.loginAttempts = loginAttempts;
        this.passwordResetService = passwordResetService;
    }

    /** New account, and already signed in: 201 Me + session cookie. */
    @Operation(operationId = "signUp")
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    MeResponse signUp(@Valid @RequestBody SignUpRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        UserView user = identityService.signUp(body.name(), body.email(), body.password());
        TrakrPrincipal principal = TrakrPrincipal.of(user);
        startSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()),
                false, request, response);
        return me(user);
    }

    @Operation(operationId = "login")
    @PostMapping("/login")
    MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        // 4. lockout check first, so a locked email costs no Argon2 hashing
        if (loginAttempts.isLocked(body.email())) {
            throw new TrakrException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many failed sign-ins. Try again in 15 minutes.");
        }

        // 1. the AuthenticationManager loads the user and compares the password with the Argon2 hash
        Authentication result;
        try {
            result = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.email(), body.password()));
        } catch (BadCredentialsException e) {
            loginAttempts.recordFailure(body.email());
            throw new TrakrException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Wrong email or password.");
        }
        loginAttempts.reset(body.email());

        TrakrPrincipal principal = ((TrakrPrincipal) result.getPrincipal()).withoutPassword();
        // 2. + 3.
        startSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, result.getAuthorities()),
                body.remember(), request, response);
        return me(identityService.findById(principal.id()).orElseThrow());
    }

    /** Always 202, whether or not the email has an account (no user enumeration). */
    @Operation(operationId = "forgotPassword")
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void forgotPassword(@Valid @RequestBody ForgotPasswordRequest body) {
        passwordResetService.requestReset(body.email());
    }

    @Operation(operationId = "resetPassword")
    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@Valid @RequestBody ResetPasswordRequest body) {
        passwordResetService.reset(body.token(), body.password());
    }

    // a filter like UsernamePasswordAuthenticationFilter would do all of this for us; a controller must do it by hand
    private void startSession(Authentication authentication, boolean remember, HttpServletRequest request,
            HttpServletResponse response) {
        // session fixation: a session id someone saw before the login must not become the logged-in one
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        HttpSession session = request.getSession();
        // 3. keep me signed in: 30 days idle in Redis, and a cookie that survives closing the browser
        if (remember) {
            session.setMaxInactiveInterval((int) REMEMBER_ME.toSeconds());
            request.setAttribute(SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR, true);
        }

        // 4. the user id as a plain string attribute: the collab app reads it from Redis to know who opened a socket,
        // without deserialising the SecurityContext (06-collab §5). Spring Session also indexes the session by it.
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, authentication.getName());

        // 2. without saveContext the login would only last for this one request
        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(authentication);
        securityContextHolderStrategy.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private MeResponse me(UserView user) {
        return MeResponse.of(user, workspaceService.currentFor(user.id()).orElse(null));
    }
}
