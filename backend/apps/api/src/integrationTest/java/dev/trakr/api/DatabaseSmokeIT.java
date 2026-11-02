package dev.trakr.api;

import static dev.trakr.api.jooq.Tables.APP_USER;
import static dev.trakr.api.jooq.Tables.WORKSPACE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trakr.kernel.Ids;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

// each test rolls back, so a reused container stays clean between runs
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class DatabaseSmokeIT {

    @Autowired
    Flyway flyway;

    @Autowired
    DSLContext dsl;

    @Test
    void flywayAppliedAllMigrations() {
        assertThat(flyway.info().applied()).hasSize(2);
    }

    @Test
    void jooqCanInsertAndReadAWorkspace() {
        UUID id = Ids.newId();

        dsl.insertInto(WORKSPACE)
                .set(WORKSPACE.ID, id)
                .set(WORKSPACE.SLUG, "acme")
                .set(WORKSPACE.NAME, "Acme")
                .execute();

        String name = dsl.select(WORKSPACE.NAME)
                .from(WORKSPACE)
                .where(WORKSPACE.ID.eq(id))
                .fetchOne(WORKSPACE.NAME);
        assertThat(name).isEqualTo("Acme");
    }

    @Test
    void citextMakesEmailUnique() {
        insertUser("A@x.com");

        assertThatThrownBy(() -> insertUser("a@x.com")).isInstanceOf(DuplicateKeyException.class);
    }

    private void insertUser(String email) {
        dsl.insertInto(APP_USER)
                .set(APP_USER.ID, Ids.newId())
                .set(APP_USER.EMAIL, email)
                .set(APP_USER.DISPLAY_NAME, "Ada")
                .set(APP_USER.INITIALS, "A")
                .set(APP_USER.AVATAR_COLOR, "pink")
                .execute();
    }
}
