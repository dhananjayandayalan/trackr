package dev.trakr.api.attachment.internal;

import static dev.trakr.jooq.Tables.APP_USER;
import static dev.trakr.jooq.Tables.ATTACHMENT;

import dev.trakr.api.issue.IssueDetails.AttachmentView;
import dev.trakr.api.issue.IssueDetails.UserRef;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/** One attachment as the API shows it — the same record the issue page lists (IssueDetails.attachments). */
@Component
public class AttachmentQueries {

    private final DSLContext dsl;

    AttachmentQueries(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<AttachmentView> view(UUID attachmentId) {
        return dsl.select(ATTACHMENT.ID, ATTACHMENT.FILE_NAME, ATTACHMENT.CONTENT_TYPE, ATTACHMENT.SIZE_BYTES,
                        APP_USER.ID, APP_USER.DISPLAY_NAME, APP_USER.INITIALS, APP_USER.AVATAR_COLOR,
                        ATTACHMENT.CREATED_AT)
                .from(ATTACHMENT)
                .join(APP_USER).on(APP_USER.ID.eq(ATTACHMENT.UPLOADER_ID))
                .where(ATTACHMENT.ID.eq(attachmentId), ATTACHMENT.DELETED_AT.isNull())
                .fetchOptional(r -> new AttachmentView(r.value1(), r.value2(), r.value3(), r.value4(),
                        new UserRef(r.value5(), r.value6(), r.value7(), r.value8()), r.value9().toInstant()));
    }
}
