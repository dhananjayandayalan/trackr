package dev.trakr.api.shared.web;

import java.util.List;

/**
 * An epic's colour tag, used by the board and the backlog. Epics have no colour column: the colour
 * follows from the epic's number, so the same epic has the same colour on every screen.
 */
public final class EpicColors {

    // VOLT pop colour tokens
    private static final List<String> PALETTE = List.of("violet", "cyan", "lime", "orange", "pink", "yellow");

    private EpicColors() {
    }

    public static String of(long epicNumber) {
        return PALETTE.get(Math.floorMod(epicNumber, PALETTE.size()));
    }
}
