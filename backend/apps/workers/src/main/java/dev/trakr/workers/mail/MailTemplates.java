package dev.trakr.workers.mail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Plain-text templates in {@code mail/*.txt}: the first line is the subject, the rest the body, and
 * {@code {{name}}} is replaced by the value of {@code name}. Plain text needs no escaping and no engine.
 */
@Component
class MailTemplates {

    record Mail(String subject, String body) {
    }

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    Mail render(String template, Map<String, String> values) {
        String text = cache.computeIfAbsent(template, MailTemplates::load);
        for (Map.Entry<String, String> value : values.entrySet()) {
            text = text.replace("{{" + value.getKey() + "}}", value.getValue());
        }
        int newline = text.indexOf('\n');
        return new Mail(text.substring(0, newline).strip(), text.substring(newline + 1).strip() + "\n");
    }

    private static String load(String template) {
        try {
            return new ClassPathResource("mail/" + template + ".txt").getContentAsString(StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
