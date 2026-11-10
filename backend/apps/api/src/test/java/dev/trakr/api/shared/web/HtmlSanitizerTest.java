package dev.trakr.api.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HtmlSanitizerTest {

    private static final String ADA = "0a48c856-2921-9312-ccd1-780e11c135b5";

    @Test
    void keepsTheAllowList() {
        String html = "<p>Ship <strong>it</strong>, <em>now</em>: <code>x</code></p>"
                + "<ul><li>one</li></ul><ol><li>two</li></ol><pre>a\nb</pre>";
        assertThat(HtmlSanitizer.sanitize(html)).isEqualTo(html);
    }

    @Test
    void removesScriptsWithTheirContent() {
        assertThat(HtmlSanitizer.sanitize("<p>hi<script>alert(1)</script></p>")).isEqualTo("<p>hi</p>");
        assertThat(HtmlSanitizer.sanitize("<style>p{}</style><p>x</p>")).isEqualTo("<p>x</p>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,<script>x</script>",
            "vbscript:x"})
    void dropsLinksWithDangerousSchemes(String href) {
        // the <a> needs an allowed href to exist at all: without one only its text stays
        assertThat(HtmlSanitizer.sanitize("<a href=\"" + href + "\">click</a>")).isEqualTo("click");
    }

    @Test
    void keepsSafeLinksAndAddsRel() {
        assertThat(HtmlSanitizer.sanitize("<a href=\"https://trakr.dev/x\" target=\"_blank\">docs</a>"))
                .isEqualTo("<a href=\"https://trakr.dev/x\" rel=\"nofollow noopener noreferrer\">docs</a>");
    }

    @Test
    void removesEventHandlersAndStyles() {
        assertThat(HtmlSanitizer.sanitize("<p onclick=\"steal()\" style=\"color:red\">x</p>")).isEqualTo("<p>x</p>");
        assertThat(HtmlSanitizer.sanitize("<img src=x onerror=alert(1)>")).isEmpty();
        assertThat(HtmlSanitizer.sanitize("<svg onload=alert(1)><p>x</p></svg>")).isEqualTo("<p>x</p>");
    }

    @Test
    void keepsMentionsButNothingElseOnSpans() {
        String mention = "<span data-mention-id=\"" + ADA + "\">&#64;Ada</span>";
        assertThat(HtmlSanitizer.sanitize("<p>hi " + mention + "</p>")).isEqualTo("<p>hi " + mention + "</p>");
        // not a user id: the attribute goes, and a span with nothing left is unwrapped
        assertThat(HtmlSanitizer.sanitize("<span data-mention-id=\"x\" class=\"evil\">@Ada</span>"))
                .isEqualTo("&#64;Ada");
    }

    @Test
    void escapesText() {
        assertThat(HtmlSanitizer.sanitize("1 < 2 & \"quotes\"")).isEqualTo("1 &lt; 2 &amp; &#34;quotes&#34;");
    }

    @Test
    void textOfSanitisedHtml() {
        String html = HtmlSanitizer.sanitize("<p>Hi <span data-mention-id=\"" + ADA + "\">@Ada</span>, "
                + "1 &lt; 2 &amp;&amp; x</p><p>line<br>two</p><ul><li>a</li><li>b</li></ul>");
        assertThat(HtmlSanitizer.text(html)).isEqualTo("Hi @Ada, 1 < 2 && x\nline\ntwo\na\nb");
    }
}
