import java.util.regex.Pattern;

/**
 * Per-creator valuation-statement patterns for transcript extraction.
 *
 * <p>Each creator phrases valuations differently:
 * <ul>
 *   <li><b>Parkev Tatevosian</b> — explicit DCF: "fair value $X", "intrinsic value $X".
 *   <li><b>Jeremy Lefebvre</b> (Financial Education main channel ONLY — never the
 *       auto-dubbed "Jeremy Lefebvre Clips" channel per Rahik's 2026-09-26 rule) —
 *       narrative: "my price target is $X", "I think it's worth $X". Often states
 *       conviction with NO number ("most upside", "best risk-reward") — those yield
 *       null, never an invented number.
 *   <li><b>Couch Investor</b> (Jose Najarro) — states personal BUYS: "I bought
 *       43 shares at $352". Buy prices are type {@code buy_price}, never mixed
 *       into fair-value columns.
 * </ul>
 *
 * <p>These patterns are a <b>candidate finder</b>, not an auto-logger. Every match
 * must be verified against the transcript (exact quote) before logging via
 * AppendEntries. Suggested types are hints for the verifier, not decisions.
 */
public final class CreatorPatterns {

    private CreatorPatterns() {}

    /** A regex pattern plus the value type it suggests when it matches. */
    public record Pat(String regex, String suggestedType) {}

    // ------------------------------------------------------------------
    // Parkev Tatevosian — the original 13 patterns (unchanged).
    // ------------------------------------------------------------------
    public static final Pat[] PARKEV = {
        new Pat("fair value.{0,80}\\$[\\d,]+", "fair_value"),
        new Pat("\\$[\\d,]+.{0,60}fair value", "fair_value"),
        new Pat("intrinsic value.{0,80}\\$[\\d,]+", "intrinsic_value"),
        new Pat("\\$[\\d,]+.{0,60}intrinsic value", "intrinsic_value"),
        new Pat("intrinsic.{0,40}\\$[\\d,]+", "intrinsic_value"),
        new Pat("price target.{0,80}\\$[\\d,]+", "price_target"),
        new Pat("\\$[\\d,]+.{0,60}price target", "price_target"),
        new Pat("my target.{0,60}\\$[\\d,]+", "price_target"),
        new Pat("valuation.{0,60}\\$[\\d,]+", "fair_value"),
        new Pat("\\$[\\d,]+.{0,40}valuation", "fair_value"),
        new Pat("worth \\$[\\d,]+", "fair_value"),
        new Pat("i (?:think|believe) (?:it's|its) worth \\$[\\d,]+", "fair_value"),
        new Pat("dcf.{0,60}\\$[\\d,]+", "fair_value"),
    };

    // ------------------------------------------------------------------
    // Jeremy Lefebvre (Financial Education main channel only).
    // Narrative style: conviction rankings, position sizes, occasional DCF.
    // Many videos state NO number — those are null with a note, never a guess.
    // ------------------------------------------------------------------
    public static final Pat[] JEREMY = {
        new Pat("my price target (?:is |of |at )?\\$[\\d,]+", "price_target"),
        new Pat("price target (?:is |of |at )?\\$[\\d,]+", "price_target"),
        new Pat("\\$[\\d,]+.{0,60}price target", "price_target"),
        new Pat("my target (?:is |of |at )?\\$[\\d,]+", "price_target"),
        new Pat("i (?:think|believe|feel) (?:it's|its|this is) worth \\$[\\d,]+", "fair_value"),
        new Pat("worth \\$[\\d,]+ (?:a|per) share", "fair_value"),
        new Pat("i value (?:it|this|the stock|them) at \\$[\\d,]+", "fair_value"),
        new Pat("fair value.{0,80}\\$[\\d,]+", "fair_value"),
        new Pat("\\$[\\d,]+.{0,60}fair value", "fair_value"),
        new Pat("intrinsic value.{0,80}\\$[\\d,]+", "intrinsic_value"),
        // Jeremy occasionally walks through a DCF on camera.
        new Pat("my dcf.{0,60}\\$[\\d,]+", "fair_value"),
        new Pat("discounted cash flow.{0,60}\\$[\\d,]+", "fair_value"),
        // "I'd pay $X" = his personal buy ceiling, closest to a valuation.
        new Pat("i'?d (?:pay|buy)(?: up to| at)? \\$[\\d,]+", "buy_limit"),
    };

    // ------------------------------------------------------------------
    // Couch Investor (Jose Najarro). Primary signal: personal BUYS with the
    // price he paid ("I purchased 43 shares of Broadcom at $352").
    // buy_price entries must never land in a fair-value column.
    // ------------------------------------------------------------------
    public static final Pat[] COUCH = {
        // Buy statements — require "at $X" so "$15,000 of stock" totals don't match.
        new Pat("i (?:just )?(?:bought|purchased)[^.]{0,80}?at \\$[\\d,.]+", "buy_price"),
        new Pat("(?:bought|purchased) at \\$[\\d,.]+(?: per share| a share)?", "buy_price"),
        new Pat("i paid \\$[\\d,.]+(?: per share| a share| for it| for them)?", "buy_price"),
        new Pat("pick(?:ed|ing) (?:it |them )?up at \\$[\\d,.]+", "buy_price"),
        // Occasional explicit valuations.
        new Pat("my price target (?:is |of |at )?\\$[\\d,]+", "price_target"),
        new Pat("price target (?:is |of |at )?\\$[\\d,]+", "price_target"),
        new Pat("fair value.{0,80}\\$[\\d,]+", "fair_value"),
        new Pat("i think it'?s worth \\$[\\d,]+", "fair_value"),
    };

    /** Return the pattern set for a creator key: parkev | jeremy | couch. */
    public static Pat[] forCreator(String creator) {
        return switch (creator.toLowerCase()) {
            case "jeremy", "jeremy_lefebvre", "jeremylefebvre" -> JEREMY;
            case "couch", "couch_investor", "couchinvestor", "jose_najarro" -> COUCH;
            default -> PARKEV;
        };
    }

    /** Combine a pattern set into one case-insensitive matcher. */
    public static Pattern combined(Pat[] pats) {
        String[] regexes = new String[pats.length];
        for (int i = 0; i < pats.length; i++) regexes[i] = pats[i].regex();
        return Pattern.compile(String.join("|", regexes),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    }

    /** Suggested type for the pattern that matched (first matching pattern wins). */
    public static String suggestedType(Pat[] pats, String matchText) {
        for (Pat p : pats) {
            if (Pattern.compile(p.regex(),
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(matchText).find()) {
                return p.suggestedType();
            }
        }
        return "unknown";
    }
}
