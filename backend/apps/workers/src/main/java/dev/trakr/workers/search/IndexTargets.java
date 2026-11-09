package dev.trakr.workers.search;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Where live writes go: always the alias, and during a reindex also the new index ("dual write", 05-search
 * §6). Writes that arrive while the reindex copies Postgres land in the new index too, so it is complete
 * when the alias switches. External versions make the order irrelevant: the newer version always wins.
 */
@Component
public class IndexTargets {

    private volatile @Nullable String dualWrite;

    public List<String> current() {
        String extra = dualWrite;
        return extra == null ? List.of(IndexManager.ALIAS) : List.of(IndexManager.ALIAS, extra);
    }

    void startDualWrite(String index) {
        dualWrite = index;
    }

    void stopDualWrite() {
        dualWrite = null;
    }
}
