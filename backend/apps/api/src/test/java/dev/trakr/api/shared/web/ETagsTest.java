package dev.trakr.api.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ETagsTest {

    @Test
    void versionRoundTripsThroughTheHeaderFormat() {
        assertThat(ETags.of(12)).isEqualTo("\"v12\"");
        assertThat(ETags.versionFrom("\"v12\"")).isEqualTo(12);
        // a proxy may turn it into a weak ETag when it compresses the response
        assertThat(ETags.versionFrom("W/\"v12\"")).isEqualTo(12);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "v12", "\"12\"", "*", "\"v-1\""})
    void missingOrForeignIfMatchIs428(String ifMatch) {
        assertThatThrownBy(() -> ETags.versionFrom(ifMatch))
                .isInstanceOfSatisfying(TrakrException.class, e -> assertThat(e.code()).isEqualTo("PRECONDITION_REQUIRED"));
    }

    @Test
    void staleVersionIs412CarryingTheCurrentResource() {
        assertThatThrownBy(() -> ETags.requireMatch(3, 4, "current"))
                .isInstanceOfSatisfying(TrakrException.VersionMismatch.class,
                        e -> assertThat(e.current()).isEqualTo("current"));
    }
}
