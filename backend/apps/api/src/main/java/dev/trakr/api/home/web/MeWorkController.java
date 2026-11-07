package dev.trakr.api.home.web;

import dev.trakr.api.home.HomeService;
import dev.trakr.api.home.HomeViews.ActivityItem;
import dev.trakr.api.home.HomeViews.IssueRow;
import dev.trakr.api.home.HomeViews.Stats;
import dev.trakr.api.home.HomeViews.Tab;
import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.shared.web.CursorPage;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 03-api §3.1, the /me/* reads of the "Your work" screen. */
@RestController
@RequestMapping("/api/v1/me")
class MeWorkController {

    private final HomeService home;

    MeWorkController(HomeService home) {
        this.home = home;
    }

    @Operation(operationId = "getMyWork")
    @GetMapping("/work")
    CursorPage<IssueRow> work(@AuthenticationPrincipal TrakrPrincipal principal,
            @RequestParam(defaultValue = "assigned") String tab, @RequestParam(required = false) @Nullable String cursor) {
        Tab which;
        try {
            which = Tab.fromValue(tab);
        } catch (IllegalArgumentException e) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Unknown tab.",
                    List.of(new FieldMessage("tab", "must be assigned, worked, viewed or starred")));
        }
        return home.work(principal.id(), which, cursor);
    }

    @Operation(operationId = "getMyStats")
    @GetMapping("/stats")
    Stats stats(@AuthenticationPrincipal TrakrPrincipal principal) {
        return home.stats(principal.id());
    }

    @Operation(operationId = "getMyActivity")
    @GetMapping("/activity")
    List<ActivityItem> activity(@AuthenticationPrincipal TrakrPrincipal principal,
            @RequestParam(defaultValue = "20") int limit) {
        return home.activity(principal.id(), limit);
    }
}
