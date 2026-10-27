package dev.trakr.api.shared.web;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// dev-only diagnostics; deleted in phase 18
@Profile("dev")
@RestController
@RequestMapping("/api/v1/_diag")
class DiagnosticsController {

    record ThreadInfo(String name, boolean virtual) {
    }

    @GetMapping("/thread")
    ThreadInfo thread() {
        Thread current = Thread.currentThread();
        return new ThreadInfo(current.getName(), current.isVirtual());
    }
}
