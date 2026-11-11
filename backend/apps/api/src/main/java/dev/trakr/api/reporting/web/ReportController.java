package dev.trakr.api.reporting.web;

import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.reporting.ReportViews.Breakdown;
import dev.trakr.api.reporting.ReportViews.Burndown;
import dev.trakr.api.reporting.ReportViews.SprintReportView;
import dev.trakr.api.reporting.ReportViews.VelocityEntry;
import dev.trakr.api.reporting.ReportingService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 03-api §3.9. {@code sprintId} absent = the active sprint, else the last closed one. */
@RestController
@RequestMapping("/api/v1/projects/{key}/reports")
class ReportController {

    private final ReportingService reports;

    ReportController(ReportingService reports) {
        this.reports = reports;
    }

    @Operation(operationId = "getBurndown")
    @GetMapping("/burndown")
    Burndown burndown(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @RequestParam(required = false) @Nullable UUID sprintId) {
        return reports.burndown(principal.id(), key, sprintId);
    }

    @Operation(operationId = "getVelocity")
    @GetMapping("/velocity")
    List<VelocityEntry> velocity(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @RequestParam(defaultValue = "" + ReportingService.DEFAULT_VELOCITY_SPRINTS) int last) {
        return reports.velocity(principal.id(), key, last);
    }

    @Operation(operationId = "getSprintReport")
    @GetMapping("/sprint")
    SprintReportView sprint(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @RequestParam(required = false) @Nullable UUID sprintId) {
        return reports.sprintReport(principal.id(), key, sprintId);
    }

    @Operation(operationId = "getBreakdown")
    @GetMapping("/breakdown")
    Breakdown breakdown(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @RequestParam(required = false) @Nullable UUID sprintId) {
        return reports.breakdown(principal.id(), key, sprintId);
    }
}
