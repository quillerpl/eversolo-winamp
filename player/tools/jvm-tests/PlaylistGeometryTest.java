import org.eversolo.winamp.skin.GenGeometry;
import org.eversolo.winamp.skin.ListMath;
import org.eversolo.winamp.skin.PlaylistGeometry;
import org.eversolo.winamp.skin.GenSprites;
import org.eversolo.winamp.skin.PleditStyle;
import org.eversolo.winamp.skin.WindowScales;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The playlist window's arithmetic, proved on a laptop.
 *
 * Sizing, scrolling and hit-testing are exactly the sort of thing that looks fine in a
 * screenshot and is one row out in the hand - and the Eversolo has no debugger to catch it.
 */
public class PlaylistGeometryTest {
    static int pass = 0, fail = 0;

    static void check(String label, Object actual, Object expected) {
        if (Objects.equals(String.valueOf(actual), String.valueOf(expected))) {
            pass++; System.out.println("    PASS  " + label + " = " + actual);
        } else {
            fail++; System.out.println("    FAIL  " + label
                    + " : expected <" + expected + "> got <" + actual + ">");
        }
    }

    /**
     * How many buttons the MISC OPTS fly-out has, read out of ZoomChooser rather than typed
     * here. ZoomChooser draws, so it imports android.graphics and cannot be compiled on this
     * JVM - but a hand-copied 4 would keep passing after somebody added a fifth item, and a
     * test that passes against the broken code is not a test.
     */
    static int flyoutItems() throws Exception {
        String dir = System.getProperty("player.dir", "player");
        String src = new String(Files.readAllBytes(Paths.get(
                dir, "skin/src/main/java/org/eversolo/winamp/skin/ZoomChooser.java")));
        Matcher m = Pattern.compile("LEVELS\\s*=\\s*\\{([^}]*)\\}").matcher(src);
        if (!m.find()) throw new IllegalStateException("ZoomChooser.LEVELS not found");
        Matcher t = Pattern.compile("TOGGLES\\s*=\\s*(\\d+)").matcher(src);
        if (!t.find()) throw new IllegalStateException("ZoomChooser.TOGGLES not found");
        if (!src.contains("ITEMS = LEVELS.length + TOGGLES")) {
            throw new IllegalStateException(
                    "ZoomChooser.ITEMS is no longer LEVELS + TOGGLES - update this test");
        }
        return m.group(1).split(",").length + Integer.parseInt(t.group(1));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Winamp's own default window ===");
        PlaylistGeometry base = PlaylistGeometry.base();
        check("width", base.width, 275);
        check("height", base.height, 232);
        check("232 is 58 + 29*6", PlaylistGeometry.CHROME_H + 29 * 6, 232);
        check("rows visible", base.visibleRows(), 12);
        check("track area x", base.trackX(), 12);
        check("track area width", base.trackW(), 243);
        check("title bar centred", base.titleX(), 87);

        System.out.println("\n=== sizing to the Eversolo's 2000x1080 ===");
        // The app window measured on the device: 2000x1080 (CLAUDE.md).
        check("x4 width fills the screen", PlaylistGeometry.widthFor(2000, 4), 500);
        check("x4 height", PlaylistGeometry.heightFor(1080, 4), 261);
        check("x4 rows", PlaylistGeometry.rowsIn(261), 15);
        check("x4 fits horizontally", 500 * 4 <= 2000, true);
        check("x4 fits vertically", 261 * 4 <= 1080, true);
        check("x7 would show too few", PlaylistGeometry.rowsIn(
                PlaylistGeometry.heightFor(1080, 7)), 6);

        System.out.println("\n=== legal sizes only: 275+25n by 58+29n ===");
        // Below the base size there is nothing to give: the window stops shrinking and
        // would overflow, which is deliberate and asserted separately underneath.
        boolean allGood = true;
        for (int px = 600; px <= 2400; px += 137) {
            for (int scale = 1; scale <= 7; scale++) {
                int w = PlaylistGeometry.widthFor(px, scale);
                int h = PlaylistGeometry.heightFor(px, scale);
                String where = px + " px at x" + scale + " -> " + w + "x" + h;
                if ((w - 275) % 25 != 0 || w < 275) {
                    fail++; allGood = false; System.out.println("    FAIL  width " + where);
                } else if ((h - 58) % 29 != 0 || h < 58 + 29 * 2) {
                    fail++; allGood = false; System.out.println("    FAIL  height " + where);
                } else if (px >= 275 * scale && w * scale > px) {
                    fail++; allGood = false; System.out.println("    FAIL  too wide " + where);
                } else if (px >= 116 * scale && h * scale > px) {
                    fail++; allGood = false; System.out.println("    FAIL  too tall " + where);
                }
            }
        }
        if (allGood) {
            pass++;
            System.out.println("    PASS  600..2400 px at x1..x7: every size legal, none overflows");
        }
        check("never smaller than the base width", PlaylistGeometry.widthFor(100, 4), 275);
        check("never fewer than two segments", PlaylistGeometry.heightFor(100, 4), 116);

        System.out.println("\n=== scrolling ===");
        PlaylistGeometry g = new PlaylistGeometry(500, 261);   // 15 rows
        check("15 rows", g.visibleRows(), 15);
        check("no scrolling with 15 tracks", g.maxOffset(15), 0);
        check("40 tracks scroll by 25", g.maxOffset(40), 25);
        check("clamp above", g.clampOffset(99, 40), 25);
        check("clamp below", g.clampOffset(-4, 40), 0);
        check("clamp with an empty list", g.clampOffset(3, 0), 0);

        check("reveal a track already on screen does nothing", g.offsetToReveal(5, 0, 40), 0);
        check("reveal below scrolls just enough", g.offsetToReveal(20, 0, 40), 6);
        check("reveal above scrolls up to it", g.offsetToReveal(2, 10, 40), 2);
        check("reveal the last track", g.offsetToReveal(39, 0, 40), 25);

        System.out.println("\n=== which row was tapped ===");
        check("first row", g.rowAt(g.trackY(), 0, 40), 0);
        check("bottom of the first row", g.rowAt(g.trackY() + 12.9f, 0, 40), 0);
        check("second row", g.rowAt(g.trackY() + 13, 0, 40), 1);
        check("scrolled by 6", g.rowAt(g.trackY() + 13, 6, 40), 7);
        check("above the list misses", g.rowAt(g.trackY() - 1, 0, 40), -1);
        check("below the list misses", g.rowAt(g.trackY() + g.trackH(), 0, 40), -1);
        check("past the end of a short list misses", g.rowAt(g.trackY() + 13 * 5, 0, 3), -1);

        System.out.println("\n=== the scrollbar ===");
        check("handle at the top", g.scrollHandleY(0, 40), g.scrollTop());
        check("handle at the bottom",
                g.scrollHandleY(25, 40), g.scrollTop() + g.scrollTravel());
        check("handle parks at the top when nothing scrolls",
                g.scrollHandleY(0, 5), g.scrollTop());
        check("dragging to the bottom scrolls to the end",
                g.offsetForHandleY(g.scrollTop() + g.scrollTravel() + 99, 40), 25);
        check("dragging above the top gives 0", g.offsetForHandleY(-50, 40), 0);
        int mid = g.offsetForHandleY(g.scrollTop() + g.scrollTravel() / 2f
                + PlaylistGeometry.SCROLL_HANDLE_H / 2f, 40);
        check("dragging to the middle lands mid-list", mid, 13);

        System.out.println("\n=== widget positions stay inside the window ===");
        PlaylistGeometry.Box close = g.closeButton();
        check("close button is in the title bar", close.y + close.h <= 20, true);
        check("close button is inside the right edge", close.x + close.w <= g.width, true);
        check("ADD is above the bottom edge",
                g.menuButton(0).y + g.menuButton(0).h <= g.height, true);
        check("LIST OPTS is inside the right edge",
                g.listButton().x + g.listButton().w <= g.width, true);
        check("the sixth transport button fits",
                g.miniTransport(5).x + g.miniTransport(5).w <= g.width, true);
        check("the scrollbar is inside the right border",
                g.scrollX() + PlaylistGeometry.SCROLL_W <= g.width, true);

        System.out.println("\n=== running time ===");
        check("zero", PlaylistGeometry.duration(0), "0:00");
        check("seconds pad", PlaylistGeometry.duration(9000), "0:09");
        check("minutes", PlaylistGeometry.duration(215000), "3:35");
        check("past an hour keeps counting in minutes",
                PlaylistGeometry.duration(6420000), "107:00");
        check("negative is not a crash", PlaylistGeometry.duration(-5), "0:00");

        System.out.println("\n=== pledit.txt colours ===");
        PleditStyle classic = PleditStyle.parse(
                "[Text]\nNormal=#00FF00\nCurrent=#FFFFFF\nNormalBG=#000000\n"
                        + "SelectedBG=#0000C6\nFont=Arial\n");
        check("normal green", Integer.toHexString(classic.normal), "ff00ff00");
        check("current white", Integer.toHexString(classic.current), "ffffffff");
        check("selection blue", Integer.toHexString(classic.selectedBg), "ff0000c6");
        check("font", classic.font, "Arial");

        PleditStyle odd = PleditStyle.parse("normal=00ff00\r\ncurrent = zzz\r\n");
        check("no-hash colours parse", Integer.toHexString(odd.normal), "ff00ff00");
        check("nonsense falls back", Integer.toHexString(odd.current), "ffffffff");
        check("missing file falls back to classic",
                Integer.toHexString(PleditStyle.parse(null).selectedBg), "ff0000c6");

        System.out.println("\n=== the browser's generic window ===");
        // The frame has no step size, so it takes the screen exactly: 2000x1080 at x4.
        GenGeometry b = new GenGeometry(500, 270);
        check("list starts below the tab row", b.listY(), 41);
        check("buttons sit inside the frame, not on it",
                b.buttonRowY() + 15 <= b.contentBottom(), true);
        check("the list stops above the buttons",
                b.listY() + b.listH() <= b.buttonRowY(), true);
        check("rows visible", b.visibleRows(), 14);
        check("scrollbar is inside the right border",
                b.scrollX() + GenGeometry.SCROLL_W <= b.width - 8, true);
        check("close button is in the title bar",
                b.closeButton().y + b.closeButton().h <= 20, true);
        check("close button is inside the right edge",
                b.closeButton().x + b.closeButton().w <= b.width, true);
        check("four tabs fit across the top",
                b.tab(3).x + b.tab(3).w < b.width, true);
        check("ADD and DONE do not overlap",
                b.bottomButton(1).x + b.bottomButton(1).w < b.bottomButton(0).x, true);
        check("first row", b.rowAt(b.listY(), 0, 40), 0);
        check("second row", b.rowAt(b.listY() + 13, 0, 40), 1);
        check("below the list misses", b.rowAt(b.listY() + b.listH(), 0, 40), -1);
        check("scrolling clamps", b.clampOffset(999, 40), 40 - 14);

        System.out.println("\n=== zoom: whole scales only, and always on screen ===");
        // The Eversolo's app window, and the three zoom settings offered in both windows.
        final int SW = 2000, SH = 1080, WANTED = 12;
        check("main window as large as it goes", WindowScales.main(SW, SH), 7);
        check("playlist's natural scale", WindowScales.natural(SW, SH, WANTED), 4);
        check("x1", WindowScales.zoomed(SW, SH, WANTED, 1f), 4);
        check("x1.5", WindowScales.zoomed(SW, SH, WANTED, 1.5f), 6);
        check("x2 backs off to what fits", WindowScales.zoomed(SW, SH, WANTED, 2f), 7);
        for (float z : new float[]{1f, 1.5f, 2f}) {
            int s = WindowScales.zoomed(SW, SH, WANTED, z);
            int pw = PlaylistGeometry.widthFor(SW, s), ph = PlaylistGeometry.heightFor(SH, s);
            check("x" + z + " playlist fits the screen",
                    pw * s <= SW && ph * s <= SH, true);
            check("x" + z + " shows at least four rows",
                    PlaylistGeometry.rowsIn(ph) >= 4, true);
            GenGeometry browser = new GenGeometry(SW / s, SH / s);
            check("x" + z + " browser tabs still fit",
                    browser.tab(3).x + browser.tab(3).w < browser.width, true);
            check("x" + z + " browser buttons still fit",
                    browser.bottomButton(2).x > GenSprites.LEFT_W, true);
        }

        System.out.println("\n=== the MISC OPTS fly-out fits inside its window ===");
        // It stacks upwards from the button that opened it, so adding FULLSCR pushed it one
        // button higher. At x2 the windows are at their shortest, which is where it would
        // run off the top first - and 2160 is what the full-screen mode asks for.
        final int ITEMS = flyoutItems();
        check("fly-out items (zoom levels + MAIN x8 + FULLSCR)", ITEMS, 5);
        for (int screenW : new int[]{2000, 2160}) {
            for (float z : new float[]{1f, 1.5f, 2f}) {
                int s2 = WindowScales.zoomed(screenW, SH, WANTED, z);
                PlaylistGeometry pg = new PlaylistGeometry(
                        PlaylistGeometry.widthFor(screenW, s2),
                        PlaylistGeometry.heightFor(SH, s2));
                int top = pg.menuButton(3).y + GenSprites.BUTTON_H * (1 - ITEMS);
                check(screenW + " x" + z + ": playlist fly-out clears the title bar",
                        top >= PlaylistGeometry.TOP_H, true);
                GenGeometry gb = new GenGeometry(screenW / s2, SH / s2);
                int btop = gb.bottomButton(2).y + GenSprites.BUTTON_H * (1 - ITEMS);
                check(screenW + " x" + z + ": browser fly-out clears the title bar",
                        btop >= GenSprites.TITLE_H, true);
            }
        }

        System.out.println("\n=== MAIN x8: one step past what fits, and what it costs ===");
        // The point of the switch is to show the crop, so the crop had better be right.
        check("x7 is what fits on the full screen", WindowScales.main(2160, SH), 7);
        check("oversize goes to x8", WindowScales.mainOversized(2160, SH), 8);
        check("x8 is 2200px wide", 275 * 8, 2200);
        check("20px off each side at 2160", WindowScales.cropPerSide(2160, 8), 20);
        check("nothing cropped at x7", WindowScales.cropPerSide(2160, 7), 0);
        check("100px off each side if the side bar is still there",
                WindowScales.cropPerSide(2000, 8), 100);
        // 116*9 = 1044 fits 1080, but 116*10 = 1160 does not - so a taller screen must not
        // be talked into a scale that crops the window top and bottom as well.
        check("refuses a step that would overflow vertically",
                WindowScales.mainOversized(4000, 1080), WindowScales.main(4000, 1080));

        System.out.println("\n=== shared list arithmetic ===");
        check("nothing to scroll", ListMath.maxOffset(5, 15), 0);
        check("clamp below zero", ListMath.clampOffset(-3, 40, 15), 0);
        check("reveal scrolls the least it can", ListMath.reveal(20, 0, 40, 15), 6);
        check("reveal upwards", ListMath.reveal(2, 10, 40, 15), 2);
        check("handle parks at the top when there is nowhere to go",
                ListMath.handleY(20, 100, 0, 0), 20);

        System.out.println("\n================================");
        System.out.println("  " + pass + " passed, " + fail + " failed");
        System.out.println("================================");
        if (fail > 0) System.exit(1);
    }
}
