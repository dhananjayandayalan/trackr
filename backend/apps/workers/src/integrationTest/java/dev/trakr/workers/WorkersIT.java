package dev.trakr.workers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The whole workers app against real containers. The schema comes from the api's own migrations
 * ({@code trakr.api.migrations} is set by Gradle to apps/api/src/main/resources/db/migration): Flyway is on
 * the test classpath only, the app itself never migrates anything.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "spring.flyway.locations=filesystem:${trakr.api.migrations}",
        "trakr.admin.token=test-admin-token"})
@Import(TestcontainersConfiguration.class)
public @interface WorkersIT {
}
