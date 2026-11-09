package dev.trakr.api.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CsvRowsTest {

    @Test
    void quotesWhatWouldBreakTheLine() {
        assertThat(CsvRows.cell("plain")).isEqualTo("plain");
        assertThat(CsvRows.cell("a, b")).isEqualTo("\"a, b\"");
        assertThat(CsvRows.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvRows.cell("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(CsvRows.cell(null)).isEmpty();
    }

    @Test
    void defusesFormulas() {
        // a spreadsheet would run these; the apostrophe makes them text
        assertThat(CsvRows.cell("=HYPERLINK(\"http://evil\")")).isEqualTo("\"'=HYPERLINK(\"\"http://evil\"\")\"");
        assertThat(CsvRows.cell("+1")).isEqualTo("'+1");
        assertThat(CsvRows.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    }
}
