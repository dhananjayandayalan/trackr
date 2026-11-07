package dev.trakr.api.issue.internal;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Plain text → safe HTML: every character that means something in HTML is escaped, blank lines
 * become paragraphs and single line breaks become {@code <br>}. Nothing the user typed can become a
 * tag, so there is nothing to sanitise.
 */
final class PlainTextHtml {

    private PlainTextHtml() {
    }

    static String of(String text) {
        return Arrays.stream(text.replace("\r\n", "\n").split("\n\\s*\n"))
                .map(paragraph -> "<p>" + escape(paragraph.strip()).replace("\n", "<br>") + "</p>")
                .collect(Collectors.joining());
    }

    private static String escape(String text) {
        StringBuilder html = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> html.append("&amp;");
                case '<' -> html.append("&lt;");
                case '>' -> html.append("&gt;");
                case '"' -> html.append("&quot;");
                case '\'' -> html.append("&#39;");
                default -> html.append(c);
            }
        }
        return html.toString();
    }
}
