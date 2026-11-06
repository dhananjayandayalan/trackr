package dev.trakr.api.identity.web;

import dev.trakr.api.identity.IdentityService;
import dev.trakr.api.identity.internal.TrakrPrincipal;
import dev.trakr.api.shared.web.ApiException;
import dev.trakr.api.workspace.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final IdentityService identityService;
    private final WorkspaceService workspaceService;

    MeController(IdentityService identityService, WorkspaceService workspaceService) {
        this.identityService = identityService;
        this.workspaceService = workspaceService;
    }

    // /api/** requires a login, so the principal is always there: from the session, via the SecurityContext
    @GetMapping
    MeResponse me(@AuthenticationPrincipal TrakrPrincipal principal) {
        return identityService.findById(principal.id())
                .map(user -> MeResponse.of(user, workspaceService.currentFor(user.id()).orElse(null)))
                // the session outlived the account
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue."));
    }
}
