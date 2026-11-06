package dev.trakr.api.project.internal;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Key ideas for a project name, best first: "Marketing Campaign" → MC, MAR, MARK, MARKE, MARKET.
 * Pure: the service checks which ones are free in the workspace.
 */
@Component
public class KeySuggester {

    private static final int MIN = 2;
    private static final int MAX = 6;

    public List<String> candidates(String name) {
        List<String> words = Arrays.stream(Normalizer.normalize(name, Normalizer.Form.NFKD)
                        .replaceAll("\\p{M}", "")
                        .toUpperCase(Locale.ROOT)
                        .split("[^A-Z]+"))
                .filter(word -> !word.isEmpty())
                .toList();
        if (words.isEmpty()) {
            return List.of("PRJ");
        }

        Set<String> candidates = new LinkedHashSet<>();
        // several words: their initials, like Jira
        if (words.size() >= MIN) {
            StringBuilder initials = new StringBuilder();
            words.stream().limit(MAX).forEach(word -> initials.append(word.charAt(0)));
            candidates.add(initials.toString());
        }
        // then longer and longer prefixes of the first word, starting at 3 letters
        String first = words.getFirst();
        for (int length = 3; length <= Math.min(first.length(), MAX); length++) {
            candidates.add(first.substring(0, length));
        }
        // a very short first word ("QA") is still a key
        if (first.length() == MIN) {
            candidates.add(first);
        }
        // a one-letter name ("X") gives nothing usable
        return candidates.isEmpty() ? List.of("PRJ") : new ArrayList<>(candidates);
    }
}
