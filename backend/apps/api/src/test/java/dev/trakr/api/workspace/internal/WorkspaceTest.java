package dev.trakr.api.workspace.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorkspaceTest {

    private static final UUID ID = UUID.fromString("0190f3b2-7c1e-7a51-9f00-3b1d2c9e4a10");

    // every slug must pass the CHECK constraint ^[a-z0-9-]{3,40}$
    @ParameterizedTest
    @CsvSource({
            "Ada Lovelace, ada-lovelace-9e4a10",
            "Élodie Durand, elodie-durand-9e4a10",
            "'  ***  ', workspace-9e4a10",
            "李雷, workspace-9e4a10",
            "An Extremely Long Display Name That Goes On And On, an-extremely-long-display-name-th-9e4a10"})
    void slugIsUrlSafeAndUniqueByIdSuffix(String ownerName, String slug) {
        assertThat(Workspace.slugOf(ownerName, ID)).isEqualTo(slug).matches("^[a-z0-9-]{3,40}$");
    }
}
