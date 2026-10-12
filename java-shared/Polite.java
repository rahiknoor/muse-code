import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polite — one shared rate-limit politeness helper for every workspace tool
 * that touches a third-party site (YouTube especially).
 *
 * <p>Rahik's rule (2026-09-28): "we can implement things like thread.sleep or
 * something better" after YouTube started flagging our datacenter IP for
 * bot-like request patterns. The "something better" is this: paced
 * inter-request gaps with jitter (not a fixed sleep), exponential backoff
 * with jitter on 429/5xx, and honoring the server's Retry-After header when
 * it sends one. One copy — TranscriptFetch.java and FeedRss.java used to
 * carry their own duplicated backoff loops.
 *
 * <p>What this does NOT do: it cannot clear an IP-level block (e.g. YouTube
 * redirecting watch pages to google.com/sorry from this datacenter). For
 * that, use a different request path (Innertube player API instead of the
 * watch page), not longer sleeps.
 *
 * <p>Usage:
 * <pre>
 *   Polite.pace("www.youtube.com", 1500);          // >=1.5s (+jitter) since last hit to this host
 *   try { ... } catch (Http429 e) {
 *       Polite.backoffSleep(attempt, retryAfterMs); // 2^attempt s + jitter, honors Retry-After
 *   }
 * </pre>
 */
public final class Polite {

    private Polite() {}

    static final Random RAND = new Random();
    static final long BACKOFF_UNIT_MS = 1000;
    static final long BACKOFF_CAP_MS = 60_000;
    static final long JITTER_MS = 1000;

    private static final ConcurrentHashMap<String, Long> LAST_HIT = new ConcurrentHashMap<>();

    /**
     * Block until at least {@code minIntervalMs} (+ up to 1s jitter) has
     * elapsed since the last {@code pace(host)} call for this host.
     * First call for a host returns immediately.
     */
    public static void pace(String host, long minIntervalMs) throws InterruptedException {
        long now = System.currentTimeMillis();
        Long prev = LAST_HIT.put(host, now);
        if (prev == null) return;
        long wait = computePaceWait(prev, now, minIntervalMs, RAND.nextInt((int) JITTER_MS + 1));
        if (wait > 0) {
            Thread.sleep(wait);
            LAST_HIT.put(host, System.currentTimeMillis());
        }
    }

    /** Pure pacing math (testable without sleeping). */
    static long computePaceWait(long prevMs, long nowMs, long minIntervalMs, long jitterMs) {
        return prevMs + minIntervalMs + jitterMs - nowMs;
    }

    /**
     * Human-shaped pacing (added 2026-09-30, Rahik's "human touch" order).
     * Gaps are drawn from an exponential distribution with a 30% chance of a
     * longer pause (3-10x mean) — a heavy-tailed shape, less metronomic than
     * uniform random delays. Honest status: the 15s mean and pause parameters
     * are OUR conservative choice (practitioner write-ups, not peer-reviewed —
     * see research notes 2026-09-30); peer-reviewed grounding for the numbers
     * is open work. First call for a host returns immediately, like pace().
     */
    public static void paceHuman(String host, long meanMs) throws InterruptedException {
        long now = System.currentTimeMillis();
        Long prev = LAST_HIT.put(host, now);
        if (prev == null) return;
        long wait = prev + computeHumanGap(meanMs) - now;
        if (wait > 0) {
            Thread.sleep(wait);
            LAST_HIT.put(host, System.currentTimeMillis());
        }
    }

    /** Pure human-gap math (testable without sleeping): exponential gap with
     *  mean {@code meanMs}; 30% of the time a longer pause (3-10x mean). */
    static long computeHumanGap(long meanMs) {
        double r = RAND.nextDouble(); // [0,1): 1-r in (0,1], so log(1-r) <= 0
        long gap = (long) (-meanMs * Math.log(1.0 - r));
        if (RAND.nextDouble() < 0.30) {
            gap = meanMs * 3 + (long) (RAND.nextDouble() * meanMs * 7);
        }
        return Math.max(0, gap);
    }

    /**
     * Pure backoff math: {@code 2^attempt} seconds + jitter, capped at 60s.
     * When the server sent Retry-After, that wins if it is longer.
     * {@code attempt} is 1-based.
     */
    static long computeBackoffMs(int attempt, long retryAfterMs, long jitterMs) {
        long exp = BACKOFF_UNIT_MS << Math.min(attempt, 10); // 2^attempt s, overflow-safe
        long wait = Math.min(exp, BACKOFF_CAP_MS) + jitterMs;
        return Math.max(wait, Math.max(0, retryAfterMs));
    }

    /** Sleep the backoff for this 1-based attempt; honors Retry-After (ms, 0 = none).
     *  Returns the milliseconds waited (for logging). */
    public static long backoffSleep(int attempt, long retryAfterMs) throws InterruptedException {
        long wait = computeBackoffMs(attempt, retryAfterMs, RAND.nextInt((int) JITTER_MS + 1));
        Thread.sleep(wait);
        return wait;
    }

    /** Backoff sleep without a Retry-After value. Returns the milliseconds waited. */
    public static long backoffSleep(int attempt) throws InterruptedException {
        return backoffSleep(attempt, 0);
    }

    // ------------------------------------------------------------------ self-test

    /** java --class-path ~/workspace/java-shared/classes ~/workspace/java-shared/Polite.java --self-test */
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test".equals(args[0])) { selfTest(); return; }
        System.err.println("usage: Polite.java --self-test");
        System.exit(1);
    }

    static void selfTest() throws Exception {
        // 1. Backoff math: exponential growth, 1-based.
        SelfTest.checkEq("attempt 1 -> ~2s base", 2000L, computeBackoffMs(1, 0, 0));
        SelfTest.checkEq("attempt 2 -> ~4s base", 4000L, computeBackoffMs(2, 0, 0));
        SelfTest.checkEq("attempt 3 -> ~8s base", 8000L, computeBackoffMs(3, 0, 0));

        // 2. Jitter adds on top.
        SelfTest.checkEq("jitter added", 2500L, computeBackoffMs(1, 0, 500));

        // 3. Retry-After wins when longer than the exponential.
        SelfTest.checkEq("retry-after honored when longer", 30000L, computeBackoffMs(1, 30000, 0));
        SelfTest.checkEq("exponential wins when longer", 8000L, computeBackoffMs(3, 1000, 0));

        // 4. Cap holds at 60s even for large attempts.
        SelfTest.checkEq("capped at 60s", 60000L, computeBackoffMs(20, 0, 0));

        // 5. Pace math: no wait on first hit, full interval on immediate re-hit.
        SelfTest.check("immediate re-hit waits ~interval",
            computePaceWait(1000, 1000, 1500, 0) == 1500);
        SelfTest.check("hit after interval waits nothing",
            computePaceWait(1000, 3000, 1500, 0) <= 0);

        // 6. Real pace(): two immediate calls take >= minInterval.
        long t0 = System.currentTimeMillis();
        pace("selftest-host-a", 400);
        pace("selftest-host-a", 400);
        long elapsed = System.currentTimeMillis() - t0;
        SelfTest.check("pace enforces the interval (>=400ms)", elapsed >= 400);

        // 7. Real pace(): different hosts do not block each other.
        t0 = System.currentTimeMillis();
        pace("selftest-host-b", 5000);
        pace("selftest-host-c", 5000);
        elapsed = System.currentTimeMillis() - t0;
        SelfTest.check("separate hosts are independent (<1s)", elapsed < 1000);

        // 8. backoffSleep(1) actually sleeps ~2s (one timed check, keeps suite fast).
        t0 = System.currentTimeMillis();
        backoffSleep(0, 2100); // attempt 0 -> 1s base, retry-after 2.1s wins
        elapsed = System.currentTimeMillis() - t0;
        SelfTest.check("backoffSleep honors retry-after (>=2.1s)", elapsed >= 2100);

        // 9. Human gap: never negative, and varies (non-mechanical).
        long hg = computeHumanGap(1000);
        SelfTest.check("human gap never negative", hg >= 0);
        boolean varied = false;
        for (int i = 0; i < 50; i++)
            if (computeHumanGap(1000) != hg) { varied = true; break; }
        SelfTest.check("human gaps vary (non-mechanical)", varied);

        // 10. Human gap: mean in a plausible band. E[gap] for meanMs=1000 is
        //     0.7*1000 + 0.3*6500 = 2650; n=2000 keeps the band far from noise.
        long sum = 0; int n = 2000, longs = 0;
        for (int i = 0; i < n; i++) {
            long g = computeHumanGap(1000);
            sum += g;
            if (g >= 3000) longs++;
        }
        double mean = (double) sum / n;
        SelfTest.check("human gap mean in plausible band (1500-4000ms)", mean >= 1500 && mean <= 4000);
        SelfTest.check("human gaps include long pauses (~30%)", longs > n / 10);

        SelfTest.finish();
    }
}
