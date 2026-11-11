package dev.trakr.api.reporting.internal;

import dev.trakr.api.reporting.ReportViews.BurndownDay;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * The burndown's numbers, as a pure function: dates and snapshots in, chart points out. No database, no clock
 * — the caller passes "today" — so a unit test can check every rule with a handful of lines.
 *
 * <ul>
 *   <li>x axis: the sprint's working days, Mon–Fri, first to last day inclusive</li>
 *   <li>ideal: a straight line from the committed points on the first working day to 0 on the last</li>
 *   <li>remaining: the latest snapshot on or before the day. The report projector writes a row only on days
 *       something happened, so a quiet day (or a weekend's work) carries the last known value forward.
 *       Before the first snapshot it is the commitment. After today: unknown (null), the line stops.</li>
 *   <li>scope change: the difference in total scope since the previous working day</li>
 * </ul>
 */
public final class BurndownMath {

    /** One row of sprint_day_snapshot, as far as the chart cares. */
    public record Snapshot(BigDecimal scope, BigDecimal remaining) {
    }

    private BurndownMath() {
    }

    public static List<LocalDate> workingDays(LocalDate start, LocalDate end) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(day);
            }
        }
        // a sprint that lies entirely on a weekend still gets its days, or there would be nothing to draw
        if (days.isEmpty()) {
            start.datesUntil(end.plusDays(1)).forEach(days::add);
        }
        return days;
    }

    public static List<BurndownDay> days(LocalDate start, LocalDate end, BigDecimal committed,
            NavigableMap<LocalDate, Snapshot> snapshots, LocalDate today) {
        List<LocalDate> days = workingDays(start, end);
        int last = days.size() - 1;
        List<BurndownDay> result = new ArrayList<>(days.size());
        BigDecimal previousScope = committed;
        for (int i = 0; i < days.size(); i++) {
            LocalDate day = days.get(i);
            // committed × (days left / all steps): 79 over 11 days → 79, 71.1, 63.2, … 0
            BigDecimal ideal = last == 0 ? BigDecimal.ZERO
                    : committed.multiply(BigDecimal.valueOf(last - i))
                            .divide(BigDecimal.valueOf(last), 1, RoundingMode.HALF_UP);
            if (day.isAfter(today)) {
                result.add(new BurndownDay(day, null, ideal, null));
                continue;
            }
            Map.Entry<LocalDate, Snapshot> latest = snapshots.floorEntry(day);
            BigDecimal scope = latest == null ? committed : latest.getValue().scope();
            BigDecimal remaining = latest == null ? committed : latest.getValue().remaining();
            BigDecimal change = scope.subtract(previousScope);
            result.add(new BurndownDay(day, remaining, ideal, change.signum() == 0 ? null : change));
            previousScope = scope;
        }
        return result;
    }
}
