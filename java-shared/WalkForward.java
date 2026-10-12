import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic purged + embargoed walk-forward split logic (de Prado,
 * "Advances in Financial Machine Learning", ch. 7).
 *
 * <p>When evaluation windows are pooled across folds, a window is PURGED if
 * its [start, end] positively overlaps an already-retained window (oldest
 * first), so the same label-period move is not double-counted; it is
 * EMBARGOED if its start falls within {@code embargoDays} after a retained
 * window's end, guarding against boundary leakage. Everything else is KEPT.
 *
 * <p>The unit of decision is the WINDOW, not the row: every row sharing one
 * fold's window must be kept or dropped together. (Dropping per row would
 * wrongly delete same-fold cross-sectional rows, whose identical windows
 * trivially "overlap" each other.) Callers group rows into windows first -
 * e.g. one window per (fold, horizon).
 *
 * <p>Overlap is positive-length only: abutting windows (end == start) do
 * NOT overlap.
 */
public final class WalkForward {

    private WalkForward() {
    }

    /** A labeled window [start, end]. Implementations should give a useful toString - it appears in drop notes. */
    public interface Window {
        LocalDate start();

        LocalDate end();
    }

    /**
     * True when [aStart, aEnd] and [bStart, bEnd] share positive length.
     * Abutting (end == start) and disjoint windows return false.
     */
    public static boolean overlaps(LocalDate aStart, LocalDate aEnd,
                                   LocalDate bStart, LocalDate bEnd) {
        LocalDate s = aStart.isAfter(bStart) ? aStart : bStart;
        LocalDate e = aEnd.isBefore(bEnd) ? aEnd : bEnd;
        return s.isBefore(e);
    }

    /** True when a and b share positive length. */
    public static boolean overlaps(Window a, Window b) {
        return overlaps(a.start(), a.end(), b.start(), b.end());
    }

    /** Outcome of {@link #purgeAndEmbargo}: the kept/purged/embargoed windows plus a drop reason per dropped window. */
    public static final class Split<T extends Window> {
        public final List<T> kept = new ArrayList<>();
        public final List<T> purged = new ArrayList<>();
        public final List<T> embargoed = new ArrayList<>();
        public final Map<T, String> notes = new LinkedHashMap<>();
    }

    /**
     * Purge + embargo over label windows, oldest start first.
     *
     * @param windows     the fold windows to decide (any order; processed oldest-start-first)
     * @param embargoDays embargo gap in days; 0 disables the embargo
     * @return kept/purged/embargoed windows with a note per dropped window
     */
    public static <T extends Window> Split<T> purgeAndEmbargo(List<T> windows,
                                                             int embargoDays) {
        List<T> ordered = new ArrayList<>(windows);
        ordered.sort(Comparator.comparing(Window::start));
        Split<T> out = new Split<>();
        for (T w : ordered) {
            T clash = null;
            for (T k : out.kept) {
                if (overlaps(w, k)) {
                    clash = k;
                    break;
                }
            }
            if (clash != null) {
                out.purged.add(w);
                out.notes.put(w, "label window overlaps kept " + clash);
                continue;
            }
            T embBy = null;
            for (T k : out.kept) {
                LocalDate kend = k.end();
                if (w.start().isAfter(kend)
                        && !w.start().isAfter(kend.plusDays(embargoDays))) {
                    embBy = k;
                    break;
                }
            }
            if (embBy != null) {
                out.embargoed.add(w);
                out.notes.put(w, "start within " + embargoDays
                        + "d after kept " + embBy + " end");
                continue;
            }
            out.kept.add(w);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Self-test: synthetic windows, no network, no files.
    // ------------------------------------------------------------------

    /** Simple named window for tests. */
    static final class W implements Window {
        final String name;
        final LocalDate s;
        final LocalDate e;

        W(String name, String s, String e) {
            this.name = name;
            this.s = LocalDate.parse(s);
            this.e = LocalDate.parse(e);
        }

        @Override
        public LocalDate start() {
            return s;
        }

        @Override
        public LocalDate end() {
            return e;
        }

        @Override
        public String toString() {
            return name + "[" + s + "," + e + "]";
        }
    }

    static void selfTest() {
        LocalDate d2020 = LocalDate.of(2020, 12, 31);
        LocalDate d2021 = LocalDate.of(2021, 12, 31);
        LocalDate d2022 = LocalDate.of(2022, 12, 31);
        LocalDate d2023 = LocalDate.of(2023, 12, 31);
        LocalDate d2024 = LocalDate.of(2024, 12, 31);

        // overlaps(): positive-length intersection only.
        SelfTest.check("overlap: abutting windows do not overlap",
                !overlaps(d2020, d2021, d2021, d2022));
        SelfTest.check("overlap: partial overlap",
                overlaps(d2020, d2022, d2021, d2023));
        SelfTest.check("overlap: containment",
                overlaps(d2020, d2024, d2021, d2022));
        SelfTest.check("overlap: identical windows",
                overlaps(d2020, d2022, d2020, d2022));
        SelfTest.check("overlap: disjoint windows",
                !overlaps(d2020, d2021, d2022, d2023));
        SelfTest.check("overlap: reversed argument order still detects",
                overlaps(d2021, d2023, d2020, d2022));
        SelfTest.check("overlap: Window overload agrees",
                overlaps(new W("a", "2020-12-31", "2022-12-31"),
                        new W("b", "2021-12-31", "2023-12-31")));

        // purgeAndEmbargo(): 2-yr label windows at yearly asofs, folds
        // 2020-2024. Fold 2021's window overlaps kept 2020 -> purged; fold
        // 2023's overlaps kept 2022 -> purged; 2020/2022/2024 -> kept.
        List<W> w2 = new ArrayList<>();
        for (int fy = 2020; fy <= 2024; fy++) {
            w2.add(new W("fold" + fy, fy + "-12-31", (fy + 2) + "-12-31"));
        }
        Split<W> s2 = purgeAndEmbargo(w2, 90);
        SelfTest.checkEq("purge h=2: kept folds",
                List.of("fold2020", "fold2022", "fold2024"),
                s2.kept.stream().map(x -> x.name).toList());
        SelfTest.checkEq("purge h=2: purged folds",
                List.of("fold2021", "fold2023"),
                s2.purged.stream().map(x -> x.name).toList());
        SelfTest.checkEq("purge h=2: nothing embargoed at yearly asofs",
                0, s2.embargoed.size());
        SelfTest.check("purge h=2: drop note names the kept window",
                s2.notes.get(s2.purged.get(0)).contains("fold2020"));

        // purgeAndEmbargo(): 1-yr label windows merely abut -> no-op.
        List<W> w1 = new ArrayList<>();
        for (int fy = 2020; fy <= 2024; fy++) {
            w1.add(new W("fold" + fy, fy + "-12-31", (fy + 1) + "-12-31"));
        }
        Split<W> s1 = purgeAndEmbargo(w1, 90);
        SelfTest.checkEq("purge h=1: 0 purged (abutting windows)",
                0, s1.purged.size());
        SelfTest.checkEq("purge h=1: 0 embargoed", 0, s1.embargoed.size());
        SelfTest.checkEq("purge h=1: all 5 kept", 5, s1.kept.size());

        // Embargo: a window starting 30d after a kept window's end is
        // embargoed; one starting 91d after is kept.
        List<W> we = List.of(
                new W("base", "2020-12-31", "2021-12-31"),
                new W("near", "2022-01-30", "2023-01-30"),
                new W("far", "2022-04-01", "2023-04-01"));
        Split<W> se = purgeAndEmbargo(we, 90);
        SelfTest.checkEq("embargo: 30d-after start is embargoed",
                List.of("near"),
                se.embargoed.stream().map(x -> x.name).toList());
        SelfTest.checkEq("embargo: 91d-after start is kept",
                List.of("base", "far"),
                se.kept.stream().map(x -> x.name).toList());
        SelfTest.checkEq("embargo: nothing purged", 0, se.purged.size());

        // Input order does not matter: oldest start is still processed first.
        List<W> rev = new ArrayList<>(w2.reversed());
        Split<W> sr = purgeAndEmbargo(rev, 90);
        SelfTest.checkEq("order: reversed input keeps the same folds",
                List.of("fold2020", "fold2022", "fold2024"),
                sr.kept.stream().map(x -> x.name).toList());

        SelfTest.finish();
    }

    public static void main(String[] args) {
        if (args.length == 1 && "--self-test".equals(args[0])) {
            selfTest();
            return;
        }
        System.out.println("usage: java WalkForward.java --self-test");
    }
}
