package dev.trakr.api;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

// no Spring context: Modulith reads the compiled classes (ArchUnit) and checks them against package-info.java
class ModularityTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(ApiApplication.class);

    // fails on a cycle, on a dependency not in allowedDependencies, or on a use of another module's internal types
    @Test
    void modulesOnlyUseWhatTheyDeclare() {
        MODULES.verify();
    }

    // C4 and UML diagrams plus one "canvas" per module, in build/spring-modulith-docs
    @Test
    void writesModuleDocumentation() {
        new Documenter(MODULES).writeDocumentation();
    }
}
