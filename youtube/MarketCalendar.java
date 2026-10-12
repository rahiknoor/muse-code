// MarketCalendar.java
// One place that knows when the NYSE is open. Replaces the pasted 10-holiday
// lists and the "do a quick search if unsure" step in six cron jobs.
//
// Usage:
//     java MarketCalendar.java --is-trading-day   # exit 0 if weekday + not an NYSE holiday
//     java MarketCalendar.java --is-open          # exit 0 if trading day + inside 06:00-16:00 ET
//                                                #         + not an early-close day
// Prints the reason on stdout either way. Exit 1 = closed.
//
// Holidays are COMPUTED, not listed: fixed-date holidays with NYSE weekend
// observance (Sat -> observed Fri before, Sun -> observed Mon after),
// nth-weekday holidays (MLK, Presidents, Memorial, Labor, Thanksgiving),
// and Good Friday via the Anonymous Gregorian Easter algorithm.
// Early closes (1 PM, treated as closed to match the old job behavior):
// day after Thanksgiving + Christmas Eve on a weekday. Add future years here;
// the full-holiday logic needs no yearly maintenance.

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

public class MarketCalendar {

    static final ZoneId ET = ZoneId.of("America/New_York");

    // Early-close dates we are certain about (1 PM close -> treated as closed).
    // Format: YYYY-MM-DD. Extend when NYSE publishes the next year's calendar.
    static final Set<String> EARLY_CLOSES = Set.of(
            "2026-11-27", // day after Thanksgiving
            "2026-12-24", // Christmas Eve (Thu)
            "2027-11-26", // day after Thanksgiving
            "2027-12-24"  // Christmas Eve (Fri)
    );

    public static void main(String[] args) {
        if (args.length == 0 || (!args[0].equals("--is-trading-day") && !args[0].equals("--is-open"))) {
            System.out.println("Usage: java MarketCalendar.java --is-trading-day | --is-open");
            System.exit(2);
        }
        ZonedDateTime now = ZonedDateTime.now(ET);
        LocalDate today = now.toLocalDate();

        String holiday = holidayName(today);
        if (holiday != null) {
            System.out.println("CLOSED: NYSE holiday (" + holiday + ")");
            System.exit(1);
        }
        DayOfWeek dow = today.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            System.out.println("CLOSED: weekend");
            System.exit(1);
        }
        if (args[0].equals("--is-trading-day")) {
            System.out.println("OPEN: regular trading day (" + today + ")");
            System.exit(0);
        }
        // --is-open
        if (EARLY_CLOSES.contains(today.toString())) {
            System.out.println("CLOSED: early-close day (1 PM)");
            System.exit(1);
        }
        LocalTime t = now.toLocalTime();
        if (t.isBefore(LocalTime.of(6, 0)) || !t.isBefore(LocalTime.of(16, 0))) {
            System.out.println("CLOSED: outside 06:00-16:00 ET window");
            System.exit(1);
        }
        System.out.println("OPEN: inside active window (" + today + " " + t.withSecond(0) + " ET)");
        System.exit(0);
    }

    /** Name of the NYSE holiday on this date, or null. Weekend observance applied. */
    static String holidayName(LocalDate d) {
        int y = d.getYear();
        Set<LocalDate> holidays = new HashSet<>();
        holidays.add(observed(LocalDate.of(y, 1, 1)));    // New Year's Day
        holidays.add(nthWeekday(y, 1, DayOfWeek.MONDAY, 3)); // MLK Day
        holidays.add(nthWeekday(y, 2, DayOfWeek.MONDAY, 3)); // Presidents Day
        holidays.add(easterSunday(y).minusDays(2));      // Good Friday
        holidays.add(lastWeekday(y, 5, DayOfWeek.MONDAY)); // Memorial Day
        holidays.add(observed(LocalDate.of(y, 6, 19)));   // Juneteenth
        holidays.add(observed(LocalDate.of(y, 7, 4)));    // Independence Day
        holidays.add(nthWeekday(y, 9, DayOfWeek.MONDAY, 1)); // Labor Day
        holidays.add(nthWeekday(y, 11, DayOfWeek.THURSDAY, 4)); // Thanksgiving
        holidays.add(observed(LocalDate.of(y, 12, 25)));  // Christmas
        if (!holidays.contains(d)) return null;
        // name it for the log line
        if (d.equals(observed(LocalDate.of(y, 1, 1)))) return "New Year's Day";
        if (d.equals(nthWeekday(y, 1, DayOfWeek.MONDAY, 3))) return "Martin Luther King Jr. Day";
        if (d.equals(nthWeekday(y, 2, DayOfWeek.MONDAY, 3))) return "Presidents Day";
        if (d.equals(easterSunday(y).minusDays(2))) return "Good Friday";
        if (d.equals(lastWeekday(y, 5, DayOfWeek.MONDAY))) return "Memorial Day";
        if (d.equals(observed(LocalDate.of(y, 6, 19)))) return "Juneteenth";
        if (d.equals(observed(LocalDate.of(y, 7, 4)))) return "Independence Day";
        if (d.equals(nthWeekday(y, 9, DayOfWeek.MONDAY, 1))) return "Labor Day";
        if (d.equals(nthWeekday(y, 11, DayOfWeek.THURSDAY, 4))) return "Thanksgiving";
        return "Christmas";
    }

    /** NYSE observance: Saturday holiday -> Friday before, Sunday -> Monday after. */
    static LocalDate observed(LocalDate d) {
        if (d.getDayOfWeek() == DayOfWeek.SATURDAY) return d.minusDays(1);
        if (d.getDayOfWeek() == DayOfWeek.SUNDAY) return d.plusDays(1);
        return d;
    }

    static LocalDate nthWeekday(int year, int month, DayOfWeek dow, int n) {
        LocalDate d = LocalDate.of(year, month, 1);
        int shift = (dow.getValue() - d.getDayOfWeek().getValue() + 7) % 7;
        return d.plusDays(shift + (n - 1) * 7L);
    }

    static LocalDate lastWeekday(int year, int month, DayOfWeek dow) {
        LocalDate d = LocalDate.of(year, month, Month.of(month).length(java.time.Year.isLeap(year)));
        int shift = (d.getDayOfWeek().getValue() - dow.getValue() + 7) % 7;
        return d.minusDays(shift);
    }

    /** Anonymous Gregorian algorithm. */
    static LocalDate easterSunday(int year) {
        int a = year % 19, b = year / 100, c = year % 100;
        int d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4, k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
