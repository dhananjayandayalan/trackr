package dev.trakr.api.comment.internal;

import dev.trakr.api.project.ProjectAccess;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Who a comment mentions: the user ids in its {@code <span data-mention-id="…">} elements (00-product §4.9).
 *
 * <p>Reads <em>sanitised</em> HTML only. That is why a regular expression is enough here: the sanitiser
 * writes every allowed span in exactly one shape (lower-case name, double quotes, an id it checked), so
 * there are no odd spellings left to miss.
 *
 * <p>Only people who can see the project count: a mention of anyone else would notify them about an
 * issue they may not open (and tell them it exists).
 */
@Component
public class MentionExtractor {

    // more mentions than this in one comment is spam, not a conversation
    static final int MAX_MENTIONS = 20;
    private static final Pattern MENTION = Pattern.compile(
            "<span data-mention-id=\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\"");

    private final ProjectAccess access;

    MentionExtractor(ProjectAccess access) {
        this.access = access;
    }

    /** The mentioned users who can see the project, each once, in the order they appear. */
    public List<UUID> mentionedMembers(String sanitizedHtml, UUID projectId) {
        return mentionedIds(sanitizedHtml).stream()
                .limit(MAX_MENTIONS)
                .filter(userId -> access.roleIn(userId, projectId).isPresent())
                .toList();
    }

    static Set<UUID> mentionedIds(String sanitizedHtml) {
        Set<UUID> ids = new LinkedHashSet<>();
        Matcher matcher = MENTION.matcher(sanitizedHtml);
        while (matcher.find()) {
            ids.add(UUID.fromString(matcher.group(1)));
        }
        return ids;
    }
}
