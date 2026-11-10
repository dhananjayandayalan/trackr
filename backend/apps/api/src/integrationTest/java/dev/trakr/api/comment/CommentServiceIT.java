package dev.trakr.api.comment;

import static dev.trakr.api.TestBrowser.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.identity.IdentityService;
import dev.trakr.api.identity.UserView;
import dev.trakr.api.issue.CreateIssue;
import dev.trakr.api.issue.IssueService;
import dev.trakr.api.issue.IssueType;
import dev.trakr.api.project.Access;
import dev.trakr.api.project.NewProject;
import dev.trakr.api.project.ProjectRole;
import dev.trakr.api.project.ProjectService;
import dev.trakr.api.project.Template;
import dev.trakr.api.shared.web.CursorPage;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.contracts.events.CommentEvent.CommentAdded;
import dev.trakr.contracts.events.CommentEvent.CommentDeleted;
import dev.trakr.contracts.events.CommentEvent.CommentEdited;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.AssertablePublishedEvents;

/**
 * The comment module with what it depends on (issue and everything under it). Each test builds its own
 * private project: an owner (admin), and whoever else the test needs.
 */
@ApplicationModuleTest(BootstrapMode.ALL_DEPENDENCIES)
@Import(TestcontainersConfiguration.class)
class CommentServiceIT {

    private final CommentService comments;
    private final IssueService issues;
    private final ProjectService projects;
    private final IdentityService identities;

    @Autowired
    CommentServiceIT(CommentService comments, IssueService issues, ProjectService projects,
            IdentityService identities) {
        this.comments = comments;
        this.issues = issues;
        this.projects = projects;
        this.identities = identities;
    }

    @Test
    void mentionsResolveToProjectMembersOnly(AssertablePublishedEvents events) {
        UserView ada = projectWithIssue("MEN");
        UserView grace = member(ada, "MEN", ProjectRole.MEMBER);
        // signed up on their own: another workspace, can't see MEN
        UserView eve = identities.signUp("Eve", uniqueEmail(), "correct-horse-7");

        CommentView comment = comments.add(ada.id(), "MEN-1", "<p>Hi " + mention(grace) + " and " + mention(eve)
                + "<script>alert(1)</script></p>");

        // BLOCK 1: stored sanitised — the script is gone, both mention spans stay (they're valid markup)
        assertThat(comment.bodyHtml()).doesNotContain("script").contains(grace.id().toString());
        assertThat(comment.version()).isZero();
        // BLOCK 2: but only Grace counts as mentioned: Eve would learn about an issue she can't open
        assertThat(events.ofType(CommentAdded.class)).singleElement().satisfies(event -> {
            assertThat(event.mentionedUserIds()).containsExactly(grace.id());
            assertThat(event.bodyText()).isEqualTo("Hi @Grace and @Eve");
            assertThat(event.issueKey()).isEqualTo("MEN-1");
            assertThat(event.sequence()).isZero();
        });
    }

    @Test
    void onlyTheAuthorEditsAndOnlyAuthorOrAdminDeletes(AssertablePublishedEvents events) {
        UserView ada = projectWithIssue("EDT");
        UserView grace = member(ada, "EDT", ProjectRole.MEMBER);
        UserView linus = member(ada, "EDT", ProjectRole.MEMBER);
        CommentView comment = comments.add(grace.id(), "EDT-1", "<p>first draft</p>");

        // an admin is not the author: editing someone else's words is not moderation
        assertThatThrownBy(() -> comments.edit(ada.id(), comment.id(), 0, "<p>changed by Ada</p>"))
                .isInstanceOf(TrakrException.Forbidden.class);

        CommentView edited = comments.edit(grace.id(), comment.id(), 0, "<p>final</p>");
        assertThat(edited.version()).isEqualTo(1);
        assertThat(edited.editedAt()).isNotNull();
        assertThat(events.ofType(CommentEdited.class)).singleElement()
                .satisfies(event -> assertThat(event.sequence()).isEqualTo(1));

        // the version Grace saw before her edit is stale now
        assertThatThrownBy(() -> comments.edit(grace.id(), comment.id(), 0, "<p>again</p>"))
                .isInstanceOf(TrakrException.VersionMismatch.class);

        // another member may not delete it; an admin may
        assertThatThrownBy(() -> comments.delete(linus.id(), comment.id(), 1))
                .isInstanceOf(TrakrException.Forbidden.class);
        comments.delete(ada.id(), comment.id(), 1);
        assertThat(events.ofType(CommentDeleted.class)).singleElement().satisfies(event -> {
            assertThat(event.actorId()).isEqualTo(ada.id());
            assertThat(event.authorId()).isEqualTo(grace.id());
        });
        assertThat(comments.list(ada.id(), "EDT-1", null).items()).isEmpty();
    }

    @Test
    void viewersReadButDoNotComment() {
        UserView ada = projectWithIssue("VWC");
        UserView vic = member(ada, "VWC", ProjectRole.VIEWER);
        comments.add(ada.id(), "VWC-1", "<p>hello</p>");

        assertThat(comments.list(vic.id(), "VWC-1", null).items()).hasSize(1);
        assertThatThrownBy(() -> comments.add(vic.id(), "VWC-1", "<p>me too</p>"))
                .isInstanceOf(TrakrException.Forbidden.class);
    }

    @Test
    void emptyAfterSanitisingIsRefused() {
        UserView ada = projectWithIssue("EMP");
        assertThatThrownBy(() -> comments.add(ada.id(), "EMP-1", "<script>alert(1)</script><p> </p>"))
                .isInstanceOfSatisfying(TrakrException.class,
                        e -> assertThat(e.errors()).extracting(TrakrException.FieldMessage::field)
                                .containsExactly("bodyHtml"));
    }

    @Test
    void listsNewestFirstInPages() {
        UserView ada = projectWithIssue("PGN");
        for (int i = 1; i <= CommentService.PAGE + 2; i++) {
            comments.add(ada.id(), "PGN-1", "<p>comment " + i + "</p>");
        }

        CursorPage<CommentView> first = comments.list(ada.id(), "PGN-1", null);
        assertThat(first.items()).hasSize(CommentService.PAGE);
        assertThat(first.items().getFirst().bodyHtml()).isEqualTo("<p>comment " + (CommentService.PAGE + 2) + "</p>");
        assertThat(first.nextCursor()).isNotNull();

        CursorPage<CommentView> second = comments.list(ada.id(), "PGN-1", first.nextCursor());
        assertThat(second.items()).extracting(CommentView::bodyHtml).containsExactly("<p>comment 2</p>",
                "<p>comment 1</p>");
        assertThat(second.nextCursor()).isNull();
    }

    private UserView projectWithIssue(String key) {
        UserView owner = identities.signUp("Ada", uniqueEmail(), "correct-horse-7");
        projects.create(owner.id(), new NewProject("Project " + key, key, Template.SCRUM, null, Access.PRIVATE, null,
                "cyan", List.of()));
        issues.create(owner.id(), new CreateIssue(key, IssueType.STORY, "Talk about me", null, null, null, null, null,
                null, null, List.of()));
        return owner;
    }

    // an account of its own, then added to the project (which also adds it to the owner's workspace)
    private UserView member(UserView owner, String key, ProjectRole role) {
        UserView user = identities.signUp(role == ProjectRole.VIEWER ? "Vic" : "Grace", uniqueEmail(),
                "correct-horse-7");
        projects.addMember(owner.id(), key, user.email(), role);
        return user;
    }

    private static String mention(UserView user) {
        return "<span data-mention-id=\"" + user.id() + "\">@" + user.displayName() + "</span>";
    }
}
