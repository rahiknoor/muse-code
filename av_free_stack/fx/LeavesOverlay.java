// LeavesOverlay.java — wind-blown leaves VFX overlay for the Humpty Dumpty 2D video.
// Renders a transparent PNG sequence (1280x720 @24fps) of leaves drifting on gusts,
// plus faint wind streaks during strong gusts. Overlaid onto the video with ffmpeg.
// Rahik's direction 2026-09-30: "visual effects of wind... leaves blowing".
// Java per his standing rule (he reads Java, not Python).
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import javax.imageio.ImageIO;
import java.io.File;
import java.util.*;

public class LeavesOverlay {
    static final int W = 1280, H = 720, FPS = 24;
    static final double DUR = 30.5;               // matches draft1
    static final int LEAF_COUNT = 42;

    static class Leaf {
        double x, y, size, rot, rotV, vx, vy, phase, alpha;
        Color color;
        int layer; // 0 = far (small), 1 = near (large)
    }

    // Watercolor-friendly autumn palette (matches the storybook scenes)
    static final Color[] PALETTE = {
        new Color(0x7A9A4E), new Color(0x94B054), new Color(0xC9A83C),
        new Color(0xD08A3E), new Color(0xB8643C), new Color(0x8FAE5A),
        new Color(0xD9BE5F), new Color(0x6E8F43),
    };

    static Leaf spawn(Random rnd, boolean anywhere) {
        Leaf L = new Leaf();
        L.layer = rnd.nextDouble() < 0.55 ? 0 : 1;
        L.size = L.layer == 0 ? 7 + rnd.nextDouble() * 8 : 13 + rnd.nextDouble() * 12;
        L.x = anywhere ? -60 + rnd.nextDouble() * (W + 120) : -60 - rnd.nextDouble() * 80;
        L.y = rnd.nextDouble() * H * 0.92;
        L.rot = rnd.nextDouble() * Math.PI * 2;
        L.rotV = (rnd.nextDouble() - 0.5) * 0.12;
        L.vx = (L.layer == 0 ? 1.6 : 2.4) + rnd.nextDouble() * 2.2; // wind pushes right
        L.vy = 0.25 + rnd.nextDouble() * 0.75;                        // gentle sink
        L.phase = rnd.nextDouble() * Math.PI * 2;
        L.alpha = L.layer == 0 ? 0.55 + rnd.nextDouble() * 0.25 : 0.75 + rnd.nextDouble() * 0.25;
        L.color = PALETTE[rnd.nextInt(PALETTE.length)];
        return L;
    }

    static void drawLeaf(Graphics2D g, Leaf L) {
        AffineTransform saved = g.getTransform();
        g.translate(L.x, L.y);
        g.rotate(L.rot);
        double s = L.size;
        // Leaf body: pointed oval from two quadratic curves
        Path2D leaf = new Path2D.Double();
        leaf.moveTo(0, -s);
        leaf.curveTo(s * 0.75, -s * 0.45, s * 0.7, s * 0.5, 0, s);
        leaf.curveTo(-s * 0.7, s * 0.5, -s * 0.75, -s * 0.45, 0, -s);
        g.setColor(new Color(L.color.getRed(), L.color.getGreen(), L.color.getBlue(),
                (int) (L.alpha * 255)));
        g.fill(leaf);
        // Center vein
        g.setStroke(new BasicStroke((float) Math.max(1, s * 0.07)));
        g.setColor(new Color(90, 70, 40, (int) (L.alpha * 200)));
        g.draw(new Line2D.Double(0, -s * 0.85, 0, s * 0.9));
        g.setTransform(saved);
    }

    public static void main(String[] args) throws Exception {
        String outDir = args[0];
        new File(outDir).mkdirs();
        int frames = (int) Math.round(DUR * FPS);
        Random rnd = new Random(7);
        ArrayList<Leaf> leaves = new ArrayList<>();
        for (int i = 0; i < LEAF_COUNT; i++) leaves.add(spawn(rnd, true));

        for (int f = 0; f < frames; f++) {
            double t = f / (double) FPS;
            // Wind gusts: slow swells so leaves surge and settle
            double gust = 1.0 + 0.55 * Math.sin(t * 0.9) + 0.3 * Math.sin(t * 2.3 + 1.7);

            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Faint wind streaks during strong gusts
            if (gust > 1.25) {
                g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int s = 0; s < 7; s++) {
                    double yy = ((s * 197 + t * 320) % (H + 200)) - 100;
                    double xx = ((s * 331 + t * 620) % (W + 400)) - 200;
                    int a = (int) (38 * Math.min(1, (gust - 1.25) * 2));
                    g.setColor(new Color(255, 255, 255, Math.min(70, a)));
                    QuadCurve2D streak = new QuadCurve2D.Double(
                            xx, yy, xx + 90, yy - 14, xx + 190, yy + 6);
                    g.draw(streak);
                }
            }

            for (Leaf L : leaves) {
                L.x += L.vx * gust;
                L.y += L.vy + Math.sin(t * 3.0 + L.phase) * 0.7;
                L.rot += L.rotV * (0.6 + gust * 0.5);
                if (L.x > W + 70 || L.y > H + 60) {
                    Leaf n = spawn(rnd, false);
                    L.x = n.x; L.y = n.y; L.size = n.size; L.rot = n.rot;
                    L.rotV = n.rotV; L.vx = n.vx; L.vy = n.vy;
                    L.phase = n.phase; L.alpha = n.alpha; L.color = n.color; L.layer = n.layer;
                }
                drawLeaf(g, L);
            }
            g.dispose();
            ImageIO.write(img, "png", new File(outDir, String.format("leaf_%04d.png", f)));
            if (f % 120 == 0) System.out.println("frame " + f + "/" + frames);
        }
        System.out.println("DONE frames=" + frames);
    }
}
