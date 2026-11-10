package dev.trakr.api.shared.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Rich text from browsers → HTML that is safe to show to everyone else (09-security §4).
 *
 * <p>An <em>allow-list</em>: only the elements and attributes named here survive (03-api §3.5); everything
 * else is removed, its text kept. A block-list ("remove script, remove onclick…") always misses one of the
 * hundreds of ways HTML can run code; an allow-list can't, because nothing it doesn't know gets through.
 * The OWASP sanitizer parses the input like a browser would (unclosed tags, odd nesting, entity tricks) and
 * writes clean, well-formed HTML back out, so what we store is what we checked.
 */
public final class HtmlSanitizer {

    private static final Pattern UUID_VALUE =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    // BLOCK 1: the policy, built once (immutable and thread-safe)
    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "strong", "em", "code", "pre", "ul", "ol", "li", "a", "span")
            // links: only these schemes, so href="javascript:…" is dropped (and with it the <a>, which needs an href)
            .allowUrlProtocols("http", "https", "mailto")
            .allowAttributes("href").onElements("a")
            // a link to another site can't reach back into our page (window.opener), and isn't an endorsement
            .requireRelsOnLinks("nofollow", "noopener", "noreferrer")
            // mentions: a span with a user id, nothing else; a span without attributes is unwrapped (text kept)
            .allowAttributes("data-mention-id").matching(UUID_VALUE).onElements("span")
            .toFactory();

    // the OWASP writer encodes these characters as numeric entities; text() turns them back
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");
    private static final Pattern BLOCK_END = Pattern.compile("(?i)<br\\s*/?>|</(p|li|pre)>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private HtmlSanitizer() {
    }

    /** Untrusted HTML in, HTML that only uses the allow-list out. */
    public static String sanitize(String html) {
        return POLICY.sanitize(html);
    }

    /**
     * The text of <em>sanitised</em> HTML (body_text: search, emails, notifications): line breaks for
     * paragraphs and {@code <br>}, no tags, entities decoded. Only safe on our own output, whose shape we
     * know — never as a sanitiser for anything else.
     */
    public static String text(String sanitizedHtml) {
        String withBreaks = BLOCK_END.matcher(sanitizedHtml).replaceAll("\n");
        String withoutTags = TAG.matcher(withBreaks).replaceAll("");
        return decode(withoutTags).replaceAll("\n{3,}", "\n\n").strip();
    }

    private static String decode(String text) {
        Matcher matcher = NUMERIC_ENTITY.matcher(text);
        StringBuilder decoded = new StringBuilder(text.length());
        while (matcher.find()) {
            int codePoint = Integer.parseInt(matcher.group(1));
            matcher.appendReplacement(decoded, Matcher.quoteReplacement(Character.toString(codePoint)));
        }
        matcher.appendTail(decoded);
        // &amp; last: "&amp;lt;" is the text "&lt;", not "<"
        return decoded.toString()
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&");
    }
}
