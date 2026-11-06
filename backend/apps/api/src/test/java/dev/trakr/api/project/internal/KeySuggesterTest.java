package dev.trakr.api.project.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KeySuggesterTest {

    private final KeySuggester suggester = new KeySuggester();

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', textBlock = """
            Marketing Campaign      | MC, MAR, MARK, MARKE, MARKET
            Mobile                  | MOB, MOBI, MOBIL, MOBILE
            QA                      | QA
            Écoles du monde         | EDM, ECO, ECOL, ECOLE, ECOLES
            A Team                  | AT
            the 2026 roadmap        | TR, THE
            42                      | PRJ
            """)
    void suggestsInitialsThenPrefixesOfTheFirstWord(String name, String expected) {
        assertThat(suggester.candidates(name)).containsExactly(expected.split(", "));
    }
}
