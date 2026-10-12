import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * HomeCoverageCheck — verifies Home.md actually organizes and links everything.
 *
 * <p>Rahik's rule: the home note is the vault's main organizer — it must link
 * every section and every side chat, and each side-chat note must explain what
 * its chat holds. This check flags gaps so the suggestion surfaces on its own
 * instead of waiting for him to notice.
 *
 * <p>Checks:
 * <ol>
 *   <li>Every top-level vault folder's keyword appears in Home.md
 *       (e.g. "Peterson" for "11 🔎 Peterson vs the Text").</li>
 *   <li>Every note in "03 💬 Chats" (except the archive) is [[linked]] from
 *       Home.md.</li>
 *   <li>Every chat note contains a Purpose line (the "explains what it holds"
 *       bar).</li>
 * </ol>
 *
 * <p>Usage: HomeCoverageCheck.java [--self-test]. Exit 0 = covered (prints
 * gaps, if any); exit 1 = gaps found; exit 2 = error.
 */
public class HomeCoverageCheck {
    static final String CHATS_DIR = "03 \uD83D\uDCAC Chats";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            System.exit(selfTest() ? 0 : 1);
        }
        Path vault = Paths.get(System.getenv("HOME"), "workspace", "atlas");
        List<String> gaps = check(vault);
        if (gaps.isEmpty()) {
            System.out.println("HomeCoverageCheck: OK — Home covers all folders and chats.");
        } else {
            System.out.println("HomeCoverageCheck: GAPS (" + gaps.size() + "):");
            gaps.forEach(g -> System.out.println("  - " + g));
            System.exit(1);
        }
    }

    static List<String> check(Path vault) throws IOException {
        List<String> gaps = new ArrayList<>();
        String home = Files.readString(vault.resolve("Home.md"), StandardCharsets.UTF_8);
        Set<String> links = new HashSet<>();
        Matcher lm = Pattern.compile("\\[\\[([^\\]|#]+)").matcher(home);
        while (lm.find()) links.add(lm.group(1).strip());

        // 1. top-level folders
        try (Stream<Path> s = Files.list(vault)) {
            for (String p : s.filter(Files::isDirectory)
                    .map(x -> x.getFileName().toString())
                    .filter(n -> !n.startsWith(".")).sorted().toList()) {
                String keyword = keyword(p);
                if (!keyword.isEmpty() && !home.toLowerCase().contains(keyword.toLowerCase())) {
                    gaps.add("folder not covered in Home: " + p + " (keyword '" + keyword + "')");
                }
            }
        }
        // 2. chat notes linked from Home
        Path chats = vault.resolve(CHATS_DIR);
        if (Files.isDirectory(chats)) {
            try (Stream<Path> s = Files.list(chats)) {
                for (String n : s.filter(x -> x.toString().endsWith(".md"))
                        .map(x -> x.getFileName().toString())
                        .map(x -> x.substring(0, x.length() - 3)).sorted().toList()) {
                    if (!links.contains(n)) gaps.add("chat note not linked from Home: " + n);
                    // 3. purpose line
                    String text = Files.readString(chats.resolve(n + ".md"), StandardCharsets.UTF_8);
                    if (!text.contains("**Purpose:**")) {
                        gaps.add("chat note has no Purpose line: " + n);
                    }
                }
            }
        }
        return gaps;
    }

    /** Strip leading number + emoji, return the first distinctive word(s). */
    static String keyword(String folder) {
        String s = folder.replaceAll("^[0-9]+\\s*", "");
        // drop emoji / pictographs
        s = s.replaceAll("[\\p{So}\\p{Sk}\\p{Sc}]+", " ").strip().replaceAll("\\s+", " ");
        if (s.isEmpty()) return "";
        // "Peterson vs the Text" -> "Peterson"; "Quran" -> "Quran"
        String first = s.split("\\s+")[0];
        if (first.equalsIgnoreCase("vs")) {
            String[] parts = s.split("\\s+");
            return parts.length > 2 ? parts[2] : s;
        }
        return first;
    }

    // ---------- self-test ----------
    static boolean selfTest() throws Exception {
        Path tmp = Files.createTempDirectory("homecov");
        Path vault = tmp.resolve("vault");
        Files.createDirectories(vault.resolve(CHATS_DIR));
        Files.writeString(vault.resolve("Home.md"),
                "# Home\n- [[Stocks]]\n- [[Chat A]]\nChats section covers the chats folder.\n", StandardCharsets.UTF_8);
        Files.createDirectories(vault.resolve("01 Stocks"));
        Files.createDirectories(vault.resolve("11 Peterson vs the Text"));
        Files.writeString(vault.resolve(CHATS_DIR).resolve("Chat A.md"),
                "# Chat A\n**Purpose:** things.\n", StandardCharsets.UTF_8);
        Files.writeString(vault.resolve(CHATS_DIR).resolve("Chat B.md"),
                "# Chat B\n**Purpose:** more things.\n", StandardCharsets.UTF_8);
        Files.writeString(vault.resolve(CHATS_DIR).resolve("Chat C.md"),
                "# Chat C\nno purpose here.\n", StandardCharsets.UTF_8);

        List<String> gaps = check(vault);
        boolean ok = true;
        ok &= must(gaps, "folder not covered in Home: 11 Peterson vs the Text", true);
        ok &= must(gaps, "chat note not linked from Home: Chat B", true);
        ok &= must(gaps, "chat note has no Purpose line: Chat C", true);
        ok &= must(gaps, "Chat A", false); // Chat A fully covered -> no gap mentions it
        System.out.println(ok ? "SELF-TEST PASS" : "SELF-TEST FAIL");
        gaps.forEach(g -> System.out.println("  gap: " + g));
        return ok;
    }

    static boolean must(List<String> gaps, String needle, boolean expect) {
        boolean found = gaps.stream().anyMatch(g -> g.contains(needle));
        boolean pass = found == expect;
        System.out.println((pass ? "ok   " : "FAIL ") + (expect ? "finds: " : "absent: ") + needle);
        return pass;
    }
}
