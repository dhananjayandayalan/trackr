package dev.trakr.collab.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The bytes make-fixtures.mjs wrote with the real JavaScript libraries (src/test/resources/fixtures). */
final class Fixtures {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Fixtures() {
    }

    static byte[] bytes(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode manifest() {
        return JSON.readTree(bytes("manifest.json"));
    }
}
