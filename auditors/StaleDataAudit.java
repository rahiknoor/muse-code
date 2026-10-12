import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/// Stale Data Auditor — Rahik's auditor fleet, auditor #3.
///
/// ONE TASK: inventory the workspace's data stores — size, last write, last
/// read, known consuming job(s) — and flag stores with no consumer activity
/// in 90+ days as keep-or-kill candidates.
///
/// READ-ONLY: never deletes, never moves. Findings end in SUGGESTED actions;
/// a separate approved pass does any implementing. Deletion always needs
/// Rahik's word (approval matrix).
///
/// Structured finding format: severity | store | expected vs actual |
/// evidence | suggestion.
public class StaleDataAudit {

    record Store(String name, String path, String consumers) {}

    static final List<Store> STORES = List.of(
            new Store("dcf_csv", "workspace/dcf_csv", "sec-filing-watch, dcf-gap-watch, morning digest, NotebookLM re-ask"),
            new Store("dcf_csv/backtest", "workspace/dcf_csv/backtest", "quarterly backtest report card"),
            new Store("creator_tracker", "workspace/creator_tracker", "youtube digest, parkev/jeremy/couch backfills"),
            new Store("youtube transcripts", "workspace/youtube", "youtube digest, buy-call alerts"),
            new Store("stock_watch", "workspace/stock_watch", "stock watch, drop alerts, gap watch"),
            new Store("sec_filings (NotebookLM uploads)", "workspace/sec_filings", "sec-filing-watch"),
            new Store("notebooklm_full_notes", "workspace/notebooklm_full_notes", "notebooklm re-ask (11-note read)"),
            new Store("research_notes", "workspace/research_notes", "on-demand reference (no scheduled consumer)"),
            new Store("atlas vault", "workspace/atlas", "atlas push, Rahik's Obsidian/iPhone"),
            new Store("your_files/chat-exports", "workspace/your_files/chat-exports", "none known"),
            new Store("self_improvement/staging", "workspace/self_improvement/staging", "self-improvement loop (auto-swept 2026-10-07)"),
            new Store("proactivity/hidden_files", "workspace/proactivity/hidden_files", "proactivity engine"),
            new Store("feed/research", "workspace/feed/research", "feed writer"),
            new Store("scheduler_state", "workspace/scheduler_state", "all scheduled jobs (watermarks, budgets, ledgers)"),
            new Store("maps_import", "workspace/maps_import", "saved-maps-places-nearby-alerts")
    );

    static final long STALE_DAYS = 90;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path home = Paths.get(home());
        List<String> rows = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        long now = Instant.now().getEpochSecond();
        for (Store s : STORES) {
            Path p = home.resolve(s.path());
            if (!Files.exists(p)) {
                rows.add(String.format("%-32s %8s %12s  %s", s.name(), "MISSING", "-", s.consumers()));
                findings.add("[MEDIUM] missing store | " + s.name() + "\n"
                        + "  expected: directory exists at " + s.path() + "\n"
                        + "  actual: not found\n"
                        + "  suggestion: if a job still references it, restore or re-point the job; otherwise drop it from this inventory.");
                continue;
            }
            long bytes = size(p);
            long newest = newestWrite(p);
            String age = newest == 0 ? "n/a" : Instant.ofEpochSecond(newest)
                    .atZone(ZoneId.of("America/New_York")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            long ageDays = newest == 0 ? -1 : (now - newest) / 86400;
            rows.add(String.format("%-32s %8s %12s  %s", s.name(), human(bytes), age, s.consumers()));
            boolean noConsumer = s.consumers().startsWith("none") || s.consumers().startsWith("on-demand");
            if (ageDays >= 0 && ageDays >= STALE_DAYS && noConsumer) {
                findings.add("[MEDIUM] stale store | " + s.name() + "\n"
                        + "  expected: a store has a consumer or recent writes\n"
                        + "  actual: last write " + age + " (" + ageDays + " days ago), consumers: " + s.consumers() + ", size " + human(bytes) + "\n"
                        + "  suggestion: keep-or-kill — say the word and it goes on the deletion candidate list (deletion itself needs your approval).");
            }
        }
        System.out.println("STALE DATA AUDIT — store inventory (read-only):\n");
        System.out.println(String.format("%-32s %8s %12s  %s", "STORE", "SIZE", "LAST WRITE", "CONSUMERS"));
        for (String r : rows) System.out.println(r);
        if (findings.isEmpty()) {
            System.out.println("\nSTALE DATA: CLEAN — every store has a consumer or recent writes.");
        } else {
            System.out.println("\nSTALE DATA: " + findings.size() + " finding(s) — read-only, nothing deleted.");
            for (String f : findings) System.out.println("\n---\n" + f);
        }
    }

    // ---- plumbing ----

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) throw new IllegalStateException("HOME not set");
        return h;
    }

    static long size(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).mapToLong(f -> {
                try { return Files.size(f); } catch (IOException e) { return 0L; }
            }).sum();
        }
    }

    static long newestWrite(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).mapToLong(f -> {
                try { return Files.getLastModifiedTime(f).toMillis() / 1000; } catch (IOException e) { return 0L; }
            }).max().orElse(0L);
        }
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "K";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + "M";
        return String.format("%.1fG", bytes / (1024.0 * 1024 * 1024));
    }

    // ---- self-test ----

    static void runSelfTest() throws Exception {
        Path tmp = Files.createTempDirectory("staledata-test");
        Path fresh = tmp.resolve("fresh");
        Path stale = tmp.resolve("stale");
        Files.createDirectories(fresh);
        Files.createDirectories(stale);
        Files.writeString(fresh.resolve("new.md"), "x");
        Path old = stale.resolve("old.md");
        Files.writeString(old, "x");
        old.toFile().setLastModified(Instant.now().minusSeconds(100L * 86400).toEpochMilli());

        check(newestWrite(fresh) > Instant.now().getEpochSecond() - 3600, "fresh store must show recent write");
        long staleAge = (Instant.now().getEpochSecond() - newestWrite(stale)) / 86400;
        check(staleAge >= 90, "stale store must show 90+ day age, got " + staleAge);
        check(human(1024 * 1024 * 3).equals("3M"), "human() must format megabytes");

        // Prove the flag logic: stale + no consumer -> flagged; stale + consumer -> not.
        List<Store> test = List.of(
                new Store("t-stale-noconsumer", tmp.resolve("stale").toString(), "none known"),
                new Store("t-stale-consumer", tmp.resolve("stale").toString(), "nightly job"));
        long now = Instant.now().getEpochSecond();
        int flags = 0;
        for (Store s : test) {
            long ageDays = (now - newestWrite(Paths.get(s.path()))) / 86400;
            boolean noConsumer = s.consumers().startsWith("none") || s.consumers().startsWith("on-demand");
            if (ageDays >= STALE_DAYS && noConsumer) flags++;
        }
        check(flags == 1, "exactly the no-consumer stale store must flag, got " + flags);

        System.out.println("StaleDataAudit self-test: ALL GREEN (age detection + flag logic proven)");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
