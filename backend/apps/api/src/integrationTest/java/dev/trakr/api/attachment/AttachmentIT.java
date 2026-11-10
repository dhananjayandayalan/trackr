package dev.trakr.api.attachment;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.trakr.api.TestBrowser;
import dev.trakr.api.TestcontainersConfiguration;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The whole upload as the browser does it: presign with the api, PUT the bytes straight to S3 (a real SeaweedFS
 * container, with java.net.http standing in for the browser's fetch), confirm with the api, download by redirect.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AttachmentIT {

    private static final String FILE = "Release notes, v2 — ünïcode.txt";
    private static final byte[] BYTES = "Hello from an attachment\n".getBytes(StandardCharsets.UTF_8);

    private final MockMvcTester mvc;
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    AttachmentIT(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void presignPutConfirmDownloadDelete() throws IOException, InterruptedException {
        TestBrowser ada = projectWithIssue("ATT");

        // BLOCK 1: presign — a URL for one PUT, and the header to send with it
        String ticket = contentOf(ada.post("/api/v1/issues/ATT-1/attachments/uploads", """
                {"fileName": "%s", "contentType": "text/plain; charset=UTF-8", "sizeBytes": %d}"""
                .formatted(FILE, BYTES.length)));
        String uploadId = JsonPath.read(ticket, "$.uploadId");
        String url = JsonPath.read(ticket, "$.url");
        Map<String, String> headers = JsonPath.read(ticket, "$.headers");
        assertThat(headers).containsExactly(Map.entry("Content-Type", "text/plain"));
        assertThat((String) JsonPath.read(ticket, "$.objectKey")).startsWith("ws/").endsWith("/" + FILE);

        // BLOCK 2: the browser's part — straight to S3, the api never sees the bytes
        HttpResponse<String> put = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", headers.get("Content-Type"))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(BYTES))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);

        // BLOCK 3: confirm — the api HEADs the object, then records it
        MvcTestResult confirmed = ada.post("/api/v1/issues/ATT-1/attachments", """
                {"uploadId": "%s"}""".formatted(uploadId));
        assertThat(confirmed).hasStatus(HttpStatus.CREATED).bodyJson()
                .hasPathSatisfying("$.fileName", v -> v.assertThat().isEqualTo(FILE))
                .hasPathSatisfying("$.contentType", v -> v.assertThat().isEqualTo("text/plain"))
                .hasPathSatisfying("$.sizeBytes", v -> v.assertThat().isEqualTo(BYTES.length))
                .hasPathSatisfying("$.uploader.displayName", v -> v.assertThat().isEqualTo("Ada"));
        String attachmentId = JsonPath.read(contentOf(confirmed), "$.id");
        // the same upload can't be recorded twice
        assertThat(ada.post("/api/v1/issues/ATT-1/attachments", """
                {"uploadId": "%s"}""".formatted(uploadId))).hasStatus(HttpStatus.NOT_FOUND);
        // and the issue page lists it
        assertThat(ada.get("/api/v1/issues/ATT-1")).bodyJson()
                .extractingPath("$.attachments[*].id").asArray().containsExactly(attachmentId);

        // BLOCK 4: download — a redirect to a presigned GET that serves the bytes as a download, with the real name
        MvcTestResult content = ada.get("/api/v1/attachments/" + attachmentId + "/content");
        assertThat(content).hasStatus(HttpStatus.FOUND).hasHeader("Cache-Control", "no-store");
        HttpResponse<byte[]> download = http.send(
                HttpRequest.newBuilder(URI.create(content.getResponse().getHeader("Location"))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(BYTES);
        assertThat(download.headers().firstValue("Content-Disposition")).hasValueSatisfying(disposition ->
                assertThat(disposition).startsWith("attachment;").contains("filename*=UTF-8''Release%20notes"));

        // BLOCK 5: delete — gone from the issue and from the API
        assertThat(ada.delete("/api/v1/attachments/" + attachmentId)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(ada.get("/api/v1/issues/ATT-1")).bodyJson().extractingPath("$.attachments").asArray().isEmpty();
        assertThat(ada.get("/api/v1/attachments/" + attachmentId + "/content")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void refusesWhatItCannotCheckOrShouldNotStore() {
        TestBrowser ada = projectWithIssue("ATR");

        // over 25 MB: refused before any URL is signed
        assertThat(ada.post("/api/v1/issues/ATR-1/attachments/uploads", """
                {"fileName": "huge.zip", "contentType": "application/zip", "sizeBytes": %d}"""
                .formatted(AttachmentService.MAX_BYTES + 1)))
                .hasStatus(HttpStatus.CONTENT_TOO_LARGE)
                .bodyJson().extractingPath("$.code").isEqualTo("ATTACHMENT_TOO_LARGE");
        // HTML and SVG can run scripts: not on the allow-list
        assertThat(ada.post("/api/v1/issues/ATR-1/attachments/uploads", """
                {"fileName": "x.svg", "contentType": "image/svg+xml", "sizeBytes": 10}"""))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("contentType");

        // signed, but never uploaded: confirm checks S3 instead of believing the browser
        String uploadId = JsonPath.read(contentOf(ada.post("/api/v1/issues/ATR-1/attachments/uploads", """
                {"fileName": "../../etc/passwd", "contentType": "text/plain", "sizeBytes": 5}""")), "$.uploadId");
        assertThat(ada.post("/api/v1/issues/ATR-1/attachments", """
                {"uploadId": "%s"}""".formatted(uploadId)))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("UPLOAD_MISSING");

        // someone else's upload id is as unknown as a made-up one
        TestBrowser eve = TestBrowser.signedUp(mvc, "Eve", uniqueEmail());
        createProject(eve, "EVE");
        assertThat(eve.post("/api/v1/issues", """
                {"projectKey": "EVE", "type": "task", "summary": "Eve's"}""")).hasStatus(HttpStatus.CREATED);
        assertThat(eve.post("/api/v1/issues/EVE-1/attachments", """
                {"uploadId": "%s"}""".formatted(uploadId))).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void theFileNameCannotEscapeItsFolder() {
        TestBrowser ada = projectWithIssue("ATN");
        String ticket = contentOf(ada.post("/api/v1/issues/ATN-1/attachments/uploads", """
                {"fileName": "..\\\\..\\\\windows\\\\evil.png", "contentType": "image/png", "sizeBytes": 5}"""));
        String objectKey = JsonPath.read(ticket, "$.objectKey");
        assertThat(objectKey).endsWith("/evil.png").doesNotContain("..").doesNotContain("\\");
    }

    private TestBrowser projectWithIssue(String key) {
        TestBrowser browser = TestBrowser.signedUp(mvc, "Ada", uniqueEmail());
        createProject(browser, key);
        assertThat(browser.post("/api/v1/issues", """
                {"projectKey": "%s", "type": "story", "summary": "With files"}""".formatted(key)))
                .hasStatus(HttpStatus.CREATED);
        return browser;
    }

    private static void createProject(TestBrowser browser, String key) {
        assertThat(browser.post("/api/v1/projects", """
                {"name": "Project %s", "key": "%s", "template": "scrum", "access": "private", "color": "cyan"}"""
                .formatted(key, key))).hasStatus(HttpStatus.CREATED);
    }

    private static String contentOf(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
