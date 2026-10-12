import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Files the sector_recal.json payloads as ours-column proposals (one per ticker). */
public class FileRecal {
    public static void main(String[] args) throws Exception {
        Proposals.initPaths();
        Path home = Paths.get(System.getenv("HOME"));
        String raw = Files.readString(home.resolve("workspace/dcf_csv/sector_recal.json"),
                StandardCharsets.UTF_8);
        Proposals.J.A arr = (Proposals.J.A) Proposals.J.parse(raw);
        int n = 0;
        for (Proposals.J.V v : arr.l) {
            Proposals.J.O p = (Proposals.J.O) v;
            Map<String, String> m = new HashMap<>();
            m.put("--column", "ours");
            m.put("--ticker", ((Proposals.J.S) p.m.get("ticker")).s);
            m.put("--new", Double.toString(Proposals.J.num(p.m.get("new_value"))));
            m.put("--rationale", ((Proposals.J.S) p.m.get("rationale")).s);
            m.put("--by", "Muse");
            m.put("--sources", "Damodaran Jan-2026 US cost-of-capital table (NYU Stern); backtest_report.md bias finding");
            m.put("--assumptions", Proposals.J.dumps(p.m.get("assumptions")));
            Proposals.cmdFile(m);
            n++;
        }
        System.out.println("filed " + n + " proposals");
    }
}
