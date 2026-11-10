package dev.trakr.api.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FileNamesTest {

    @Test
    void keepsOnlyTheLastPathSegment() {
        assertThat(FileNames.safe("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FileNames.safe("C:\\Users\\ada\\report.pdf")).isEqualTo("report.pdf");
    }

    @Test
    void removesControlCharactersAndLeadingDots() {
        assertThat(FileNames.safe("evil\r\nname.txt")).isEqualTo("evilname.txt");
        assertThat(FileNames.safe("..hidden")).isEqualTo("hidden");
        assertThat(FileNames.safe("..")).isEqualTo("file");
        assertThat(FileNames.safe("   ")).isEqualTo("file");
    }

    @Test
    void shortensLongNamesButKeepsTheExtension() {
        String safe = FileNames.safe("a".repeat(300) + ".png");
        assertThat(safe).hasSize(FileNames.MAX).endsWith("aaa.png");
        // 130 emoji (each two chars): cut by code point, never between the two halves of one
        String emoji = FileNames.safe("😀".repeat(130));
        assertThat(emoji.codePointCount(0, emoji.length())).isEqualTo(FileNames.MAX);
    }

    @Test
    void dispositionHasAnAsciiFallbackAndTheUtf8Name() {
        assertThat(FileNames.disposition(false, "Résumé \"final\".pdf"))
                .isEqualTo("attachment; filename=\"R_sum_ 'final'.pdf\"; filename*=UTF-8''R%C3%A9sum%C3%A9%20%22final%22.pdf");
        assertThat(FileNames.disposition(true, "a.png")).startsWith("inline;");
    }
}
