package dev.trakr.api.notification;

import dev.trakr.api.TestcontainersConfiguration.OwnerJdbc;
import static dev.trakr.api.TestBrowser.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.trakr.api.TestBrowser;
import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.kernel.Ids;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** 03-api §3.10 for a fresh user, whose rows are written here the way the workers' notifier writes them. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationApiIT {

    private static final UUID ADA = TestBrowser.seedId("user:ada");
    private static final UUID MAR_24 = TestBrowser.seedId("MAR:issue:24");

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;

    @Autowired
    NotificationApiIT(MockMvcTester mvc, OwnerJdbc owner) {
        this.mvc = mvc;
        this.jdbc = owner.client();
    }

    @Test
    void listCountAndMarkRead() throws Exception {
        String email = uniqueEmail();
        TestBrowser me = TestBrowser.signedUp(mvc, "Reader", email);
        UUID myId = userId(email);
        List<UUID> ids = List.of(notify(myId, "assigned", 30), notify(myId, "mentioned", 20),
                notify(myId, "commented", 10));

        assertThat(me.get("/api/v1/notifications/unread-count")).bodyJson().extractingPath("$.count").isEqualTo(3);
        String page = me.get("/api/v1/notifications").getResponse().getContentAsString();
        // newest first, texts from the payload
        assertThat(JsonPath.<List<String>>read(page, "$.items[*].kind"))
                .containsExactly("commented", "mentioned", "assigned");
        assertThat(JsonPath.<String>read(page, "$.items[0].issueKey")).isEqualTo("MAR-24");
        assertThat(JsonPath.<String>read(page, "$.items[0].actorName")).isEqualTo("Ada Lovelace");
        assertThat(JsonPath.<Object>read(page, "$.nextCursor")).isNull();

        // an id that isn't mine is ignored, not an error; the answer is the new badge number
        assertThat(me.post("/api/v1/notifications/read", """
                {"ids": ["%s", "%s"]}""".formatted(ids.get(0), Ids.newId())))
                .hasStatusOk().bodyJson().extractingPath("$.count").isEqualTo(2);
        assertThat(me.get("/api/v1/notifications?unread=true")).bodyJson()
                .extractingPath("$.items[*].kind").asArray().containsExactly("commented", "mentioned");

        assertThat(me.post("/api/v1/notifications/read", "{\"all\": true}")).bodyJson()
                .extractingPath("$.count").isEqualTo(0);
        assertThat(me.get("/api/v1/notifications")).bodyJson()
                .extractingPath("$.items[*].readAt").asArray().doesNotContainNull();
    }

    @Test
    void pagesFollowTheCursor() throws Exception {
        String email = uniqueEmail();
        TestBrowser me = TestBrowser.signedUp(mvc, "Busy", email);
        UUID myId = userId(email);
        for (int i = 0; i < NotificationService.PAGE + 3; i++) {
            notify(myId, "commented", i);
        }

        String first = me.get("/api/v1/notifications").getResponse().getContentAsString();
        String cursor = JsonPath.read(first, "$.nextCursor");
        String second = me.get("/api/v1/notifications?cursor=" + cursor).getResponse().getContentAsString();

        assertThat(JsonPath.<List<?>>read(first, "$.items")).hasSize(NotificationService.PAGE);
        assertThat(JsonPath.<List<?>>read(second, "$.items")).hasSize(3);
        assertThat(JsonPath.<Object>read(second, "$.nextCursor")).isNull();
    }

    private UUID userId(String email) {
        return jdbc.sql("SELECT id FROM app_user WHERE email = CAST(? AS citext)").params(email)
                .query(UUID.class).single();
    }

    private UUID notify(UUID userId, String kind, int minutesAgo) {
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO notification (id, user_id, kind, issue_id, actor_id, payload, created_at)
                        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), now() - make_interval(mins => ?))""")
                .params(id, userId, kind, MAR_24, ADA,
                        "{\"issueKey\": \"MAR-24\", \"summary\": \"Polish pricing table\", \"actorName\": \"Ada Lovelace\"}",
                        minutesAgo)
                .update();
        return id;
    }
}
