package dev.trakr.collab;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The whole collab app on a random port, against real containers. The schema comes from the api's migrations
 * ({@code trakr.api.migrations} is set by Gradle); Flyway is on the test classpath only — the app never migrates.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.locations=filesystem:${trakr.api.migrations}",
        "trakr.app.origin=" + Fixtures.ORIGIN})
@Import(TestcontainersConfiguration.class)
public @interface CollabIT {
}
