package dev.trakr.api.search.web;

import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.search.FilterService;
import dev.trakr.api.search.SearchViews.FilterPatch;
import dev.trakr.api.search.SearchViews.FilterView;
import dev.trakr.api.search.SearchViews.NewFilter;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 03-api §3.8: saved filters. */
@RestController
@RequestMapping("/api/v1/filters")
class FilterController {

    private final FilterService filters;

    FilterController(FilterService filters) {
        this.filters = filters;
    }

    // Boolean, not boolean: Jackson 3 rejects a missing primitive, and starred/shared are optional
    record CreateFilterRequest(@NotBlank @Size(max = 80) String name, @NotNull @Size(max = 2000) String tql,
            @Nullable Boolean starred, @Nullable Boolean shared) {
    }

    record PatchFilterRequest(@Nullable @Size(max = 80) String name, @Nullable @Size(max = 2000) String tql,
            @Nullable Boolean starred, @Nullable Boolean shared) {
    }

    @Operation(operationId = "listFilters")
    @GetMapping
    List<FilterView> list(@AuthenticationPrincipal TrakrPrincipal principal) {
        return filters.list(principal.id());
    }

    @Operation(operationId = "createFilter")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    FilterView create(@AuthenticationPrincipal TrakrPrincipal principal,
            @Valid @RequestBody CreateFilterRequest request) {
        return filters.create(principal.id(), new NewFilter(request.name(), request.tql(),
                Boolean.TRUE.equals(request.starred()), Boolean.TRUE.equals(request.shared())));
    }

    @Operation(operationId = "updateFilter")
    @PatchMapping(value = "/{id}", consumes = { "application/merge-patch+json", "application/json" })
    FilterView patch(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody PatchFilterRequest request) {
        return filters.patch(principal.id(), id,
                new FilterPatch(request.name(), request.tql(), request.starred(), request.shared()));
    }

    @Operation(operationId = "deleteFilter")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable UUID id) {
        filters.delete(principal.id(), id);
    }
}
