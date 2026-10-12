import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Regression suite for the proposal system (Proposals.java: filing,
 * aging, and the approve-path staleness guard).
 *
 * <p>Kept as a SEPARATE file on purpose: Proposals.java is invoked from a
 * dozen cron bodies WITHOUT --class-path, and the single-file java
 * launcher compiles the whole file -- a SelfTest reference inside
 * Proposals.java would break every one of those call sites. This companion
 * is the only file that needs the shared harness. Run it by compiling both
 * files together (same pattern as the digest cron and RunAllTests'
 * EXTRA_SOURCES):
 *
 * <pre>
 *   export JAVA_HOME=~/workspace/java/jdk-21; export PATH=$JAVA_HOME/bin:$PATH
 *   javac -cp "$HOME/workspace/java-shared/classes" -d /tmp/prop_test \
 *         ~/workspace/dcf_csv/Proposals.java ~/workspace/dcf_csv/ProposalsSelfTest.java
 *   java -cp "$HOME/workspace/java-shared/classes:/tmp/prop_test" ProposalsSelfTest
 * </pre>
 *
 * <p>Picked up by the weekly regression sweep via RunAllTests.java
 * (EXTRA_SOURCES entry compiles it together with Proposals.java).
 * Prove-can-fail (2026-09-30): with AGE_ESCALATE_DAYS temporarily broken
 * (7 -&gt; 36500) the suite goes red on the bucket, stale-for-approval, and
 * guard-refusal assertions and exits 1; restored, it is green.
 */
public class ProposalsSelfTest {

    public static void main(String[] args) throws IOException {
        Proposals.initPaths(); // guardNotStale writes the changelog -> needs paths
        cmdSelfTest();
    }

    static Proposals.J.O synthProposal(String id, String kind, int ageDaysBack) {
        Proposals.J.O p = new Proposals.J.O();
        p.m.put("id", new Proposals.J.S(id));
        if (kind != null) p.m.put("kind", new Proposals.J.S(kind));
        p.m.put("column", new Proposals.J.S("notebooklm"));
        p.m.put("ticker", new Proposals.J.S("TEST"));
        p.m.put("proposed_at",
                new Proposals.J.S(Instant.now().minus(Duration.ofDays(ageDaysBack)).toString()));
        p.m.put("status", new Proposals.J.S("pending"));
        return p;
    }

    static void cmdSelfTest() throws IOException {
        // --- age buckets: value proposals (FLAG 3d / ESCALATE 7d) ---
        SelfTest.checkEq("value 0d -> fresh", "fresh", Proposals.ageBucket(synthProposal("v0", null, 0)));
        SelfTest.checkEq("value 2d -> fresh", "fresh", Proposals.ageBucket(synthProposal("v2", null, 2)));
        SelfTest.checkEq("value 3d -> flag", "flag", Proposals.ageBucket(synthProposal("v3", null, 3)));
        SelfTest.checkEq("value 6d -> flag", "flag", Proposals.ageBucket(synthProposal("v6", null, 6)));
        SelfTest.checkEq("value 7d -> escalate", "escalate", Proposals.ageBucket(synthProposal("v7", null, 7)));
        SelfTest.checkEq("value 30d -> escalate", "escalate", Proposals.ageBucket(synthProposal("v30", null, 30)));
        // --- age buckets: voice-evidence (FLAG 14d / ESCALATE 30d) ---
        SelfTest.checkEq("evidence 13d -> fresh", "fresh", Proposals.ageBucket(synthProposal("e13", "voice-evidence", 13)));
        SelfTest.checkEq("evidence 14d -> flag", "flag", Proposals.ageBucket(synthProposal("e14", "voice-evidence", 14)));
        SelfTest.checkEq("evidence 29d -> flag", "flag", Proposals.ageBucket(synthProposal("e29", "voice-evidence", 29)));
        SelfTest.checkEq("evidence 30d -> escalate", "escalate", Proposals.ageBucket(synthProposal("e30", "voice-evidence", 30)));
        // --- fail-safe: bad timestamps escalate ---
        Proposals.J.O bad = synthProposal("bad", null, 0);
        bad.m.put("proposed_at", new Proposals.J.S("not-a-timestamp"));
        SelfTest.checkEq("malformed proposed_at -> escalate", "escalate", Proposals.ageBucket(bad));
        Proposals.J.O missing = synthProposal("missing", null, 0);
        missing.m.remove("proposed_at");
        SelfTest.checkEq("missing proposed_at -> escalate", "escalate", Proposals.ageBucket(missing));
        // --- price sensitivity ---
        SelfTest.check("value proposal is price-sensitive", Proposals.priceSensitive(synthProposal("ps1", null, 0)));
        SelfTest.check("voice-evidence is NOT price-sensitive",
                !Proposals.priceSensitive(synthProposal("ps2", "voice-evidence", 0)));
        // --- stale-for-approval predicate ---
        SelfTest.check("7d value proposal is stale-for-approval", Proposals.staleForApproval(synthProposal("s1", null, 7)));
        SelfTest.check("6d value proposal is NOT stale-for-approval",
                !Proposals.staleForApproval(synthProposal("s2", null, 6)));
        SelfTest.check("60d evidence is NOT stale-for-approval (exempt)",
                !Proposals.staleForApproval(synthProposal("s3", "voice-evidence", 60)));
        // --- the approve-path guard itself (the exact method cmdApprove calls) ---
        boolean freshPasses = true;
        try {
            Proposals.guardNotStale(synthProposal("g1", null, 1), "g1");
        } catch (Exception e) {
            freshPasses = false;
        }
        SelfTest.check("guard passes a fresh value proposal", freshPasses);
        boolean staleRefused = false;
        try {
            Proposals.guardNotStale(synthProposal("g2", null, 9), "g2");
        } catch (IllegalArgumentException e) {
            staleRefused = true;
        } catch (Exception e) {
        }
        SelfTest.check("guard REFUSES an escalated value proposal", staleRefused);
        boolean evidencePasses = true;
        try {
            Proposals.guardNotStale(synthProposal("g3", "voice-evidence", 60), "g3");
        } catch (Exception e) {
            evidencePasses = false;
        }
        SelfTest.check("guard passes old voice-evidence (exempt)", evidencePasses);
        // --- stale stamp round-trip (temp files, never the live queue) ---
        Path tmp = Files.createTempDirectory("proposals-age-test");
        Path f = tmp.resolve("t.json");
        Proposals.J.O sp = synthProposal("st1", null, 9);
        Files.writeString(f, Proposals.J.dumps(sp) + "\n", StandardCharsets.UTF_8);
        Proposals.stampStale(f, sp, "escalate", Instant.now());
        Proposals.J.O re = (Proposals.J.O) Proposals.J.parse(Files.readString(f, StandardCharsets.UTF_8));
        SelfTest.check("stamp sets stale=true",
                re.m.get("stale") instanceof Proposals.J.B && ((Proposals.J.B) re.m.get("stale")).b);
        SelfTest.checkEq("stamp sets stale_bucket", "escalate", Proposals.J.str(re.m.get("stale_bucket")));
        String since1 = Proposals.J.str(re.m.get("stale_since"));
        SelfTest.check("stamp sets stale_since", since1 != null);
        Proposals.stampStale(f, re, "escalate", Instant.now().plus(Duration.ofDays(3)));
        Proposals.J.O re2 = (Proposals.J.O) Proposals.J.parse(Files.readString(f, StandardCharsets.UTF_8));
        SelfTest.checkEq("re-stamp never overwrites stale_since", since1, Proposals.J.str(re2.m.get("stale_since")));
        // --- dedup logic ---
        Proposals.J.O state = new Proposals.J.O();
        List<Proposals.J.O> pend = new ArrayList<>();
        pend.add(synthProposal("d1", null, 4)); // flag
        pend.add(synthProposal("d2", null, 9)); // escalate
        pend.add(synthProposal("d3", null, 0)); // fresh
        Instant nowT = Instant.now();
        List<Proposals.J.O> n1 = Proposals.computeNudges(pend, state, nowT);
        SelfTest.checkEq("first run nudges flag+escalate", 2, n1.size());
        List<Proposals.J.O> n2 = Proposals.computeNudges(pend, state, nowT);
        SelfTest.checkEq("second run is silent (dedup)", 0, n2.size());
        pend.set(0, synthProposal("d1", null, 9)); // upgrade flag -> escalate
        List<Proposals.J.O> n3 = Proposals.computeNudges(pend, state, nowT);
        SelfTest.checkEq("upgrade re-nudges once", 1, n3.size());
        SelfTest.checkEq("upgrade why", "upgraded-to-escalate", Proposals.J.str(n3.get(0).m.get("nudge_why")));
        Proposals.J.O rec = Proposals.J.obj(state.m.get("d2"));
        rec.m.put("last_nudged_at", new Proposals.J.S(nowT.minus(Duration.ofDays(8)).toString()));
        List<Proposals.J.O> n4 = Proposals.computeNudges(pend, state, nowT);
        SelfTest.checkEq("weekly re-ping while escalated", 1, n4.size());
        SelfTest.checkEq("re-ping why", "still-escalated", Proposals.J.str(n4.get(0).m.get("nudge_why")));
        SelfTest.checkEq("re-ping targets the stale one", "d2", Proposals.J.str(n4.get(0).m.get("id")));
        Proposals.J.O st2 = new Proposals.J.O();
        Proposals.J.O rec2 = new Proposals.J.O();
        rec2.m.put("bucket", new Proposals.J.S("flag"));
        rec2.m.put("last_nudged_at", new Proposals.J.S(nowT.toString()));
        st2.m.put("d9", rec2);
        List<Proposals.J.O> n5 = Proposals.computeNudges(List.of(synthProposal("d9", null, 0)), st2, nowT);
        SelfTest.checkEq("fresh proposal: no nudge, state pruned", 0, n5.size());
        SelfTest.check("fresh proposal: state entry removed", !st2.m.containsKey("d9"));

        SelfTest.finish();
    }
}
