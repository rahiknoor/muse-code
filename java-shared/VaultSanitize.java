import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Vault-write guard: one long-lived Sanitize child for a whole run.
 *
 * <p>Extracted 2026-09-30 from RefreshValuations (Rahik's DRY rule): every
 * script that writes into the Atlas vault sanitizes through this — one
 * implementation, not one per script. Replaces the old one-JVM-per-note
 * sanitize(): the JVM was the bottleneck (32 JVM launches ≈ most of the
 * runtime).
 *
 * <p>Usage: {@code try (VaultSanitize s = new VaultSanitize()) {
 * s.sanitize(text); }} — the caller falls back to a one-shot child when the
 * batch child dies mid-run (see RefreshValuations).
 */
public final class VaultSanitize implements AutoCloseable {

    private final Process proc;
    private final DataOutputStream out;
    private final DataInputStream in;

    /** Home dir; trusts $HOME (the JVM's user.home is /root here). */
    public static Path homeDir() {
        String home = System.getenv("HOME");
        return Paths.get((home == null || home.isEmpty())
                ? System.getProperty("user.home") : home);
    }

    public VaultSanitize() throws IOException {
        String javaBin =
                Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String sanitizeJava =
                homeDir().resolve("workspace/atlas-scripts/Sanitize.java").toString();
        String sharedClasses =
                homeDir().resolve("workspace/java-shared/classes").toString();
        // -Dfile.encoding=UTF-8: the child prints non-ASCII note text
        // (—, €, →, …); without it, an ASCII-default locale would mangle
        // it to '?'. --class-path: Sanitize's self-test references the
        // shared SelfTest harness, so the child must compile with it.
        proc = new ProcessBuilder(javaBin, "-Dfile.encoding=UTF-8",
                        "--class-path", sharedClasses,
                        sanitizeJava, "--batch-stdin")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        out = new DataOutputStream(proc.getOutputStream());
        in = new DataInputStream(proc.getInputStream());
    }

    /** Sanitize one note through the child; throws if the child died. */
    public String sanitize(String dirty) throws IOException {
        byte[] bytes = dirty.getBytes(StandardCharsets.UTF_8);
        out.writeBytes("SANITIZE");
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
        byte[] magic = new byte[2];
        try {
            in.readFully(magic);
        } catch (EOFException e) {
            throw new IOException("sanitizer child exited unexpectedly", e);
        }
        if (magic[0] != 'O' || magic[1] != 'K') {
            throw new IOException("sanitizer protocol error");
        }
        int len = in.readInt();
        if (len < 0 || len > 50_000_000) {
            throw new IOException("sanitizer bad length " + len);
        }
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        try {
            out.close(); // EOF -> child exits on its own
        } catch (IOException ignored) {
        }
        proc.destroy();
    }
}
