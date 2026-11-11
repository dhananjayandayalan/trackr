package dev.trakr.api.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.trakr.api.reporting.ReportViews.BurndownDay;
import dev.trakr.api.reporting.internal.BurndownMath.Snapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class BurndownMathTest {

    private static final LocalDate MON = LocalDate.parse("2026-11-02");
    private static final LocalDate END = LocalDate.parse("2026-11-16");

    @Test
    void workingDaysSkipWeekends() {
        assertThat(BurndownMath.workingDays(MON, END)).extracting(LocalDate::getDayOfMonth)
                .containsExactly(2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 16);
        // a sprint that is only a weekend still has days to draw
        assertThat(BurndownMath.workingDays(LocalDate.parse("2026-11-07"), LocalDate.parse("2026-11-08"))).hasSize(2);
    }

    @Test
    void idealIsAStraightLineFromCommittedToZero() {
        List<BurndownDay> days = BurndownMath.days(MON, END, new BigDecimal("79"), new TreeMap<>(), MON);

        assertThat(days).extracting(BurndownDay::ideal).map(BigDecimal::toPlainString)
                .containsExactly("79.0", "71.1", "63.2", "55.3", "47.4", "39.5", "31.6", "23.7", "15.8", "7.9", "0.0");
    }

    @Test
    void remainingCarriesTheLastSnapshotForwardAndStopsAtToday() {
        NavigableMap<LocalDate, Snapshot> snapshots = new TreeMap<>();
        snapshots.put(MON, snapshot(79, 79));
        snapshots.put(LocalDate.parse("2026-11-04"), snapshot(79, 74));
        // work on Saturday shows on Monday
        snapshots.put(LocalDate.parse("2026-11-07"), snapshot(79, 70));

        List<BurndownDay> days = BurndownMath.days(MON, END, new BigDecimal("79"), snapshots, LocalDate.parse("2026-11-09"));

        assertThat(days.subList(0, 6)).extracting(day -> day.remaining().intValue())
                .containsExactly(79, 79, 74, 74, 74, 70);
        assertThat(days.subList(6, 11)).extracting(BurndownDay::remaining).containsOnlyNulls();
    }

    @Test
    void scopeChangesAreTheDifferenceToThePreviousWorkingDay() {
        NavigableMap<LocalDate, Snapshot> snapshots = new TreeMap<>();
        snapshots.put(MON, snapshot(20, 20));
        snapshots.put(LocalDate.parse("2026-11-05"), snapshot(28, 25));
        snapshots.put(LocalDate.parse("2026-11-06"), snapshot(25, 22));

        List<BurndownDay> days = BurndownMath.days(MON, END, new BigDecimal("20"), snapshots, LocalDate.parse("2026-11-06"));

        assertThat(days.subList(0, 5)).extracting(BurndownDay::scopeChange)
                .containsExactly(null, null, null, new BigDecimal("8"), new BigDecimal("-3"));
    }

    @Test
    void beforeTheFirstSnapshotRemainingIsTheCommitment() {
        List<BurndownDay> days = BurndownMath.days(MON, END, new BigDecimal("12.5"), new TreeMap<>(), MON.plusDays(1));

        assertThat(days.get(0).remaining()).isEqualByComparingTo("12.5");
        assertThat(days.get(1).remaining()).isEqualByComparingTo("12.5");
        assertThat(days.get(2).remaining()).isNull();
    }

    private static Snapshot snapshot(int scope, int remaining) {
        return new Snapshot(BigDecimal.valueOf(scope), BigDecimal.valueOf(remaining));
    }
}
