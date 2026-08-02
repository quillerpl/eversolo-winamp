import org.eversolo.winamp.skin.LyricsGeometry;
import org.eversolo.winamp.tags.LrcParser;

import java.util.Objects;

/**
 * The `.lrc` parser and the "which line is being sung" search.
 *
 * These files come from strangers, so the cases that matter are the malformed ones: a
 * chorus pointed at from four places, a stray credit line with no time, an offset tag,
 * Windows line endings, a byte-order mark. The happy path is the easy part.
 */
public class LrcTest {
    static int pass = 0, fail = 0;

    static void check(String label, Object actual, Object expected) {
        if (Objects.equals(String.valueOf(actual), String.valueOf(expected))) {
            pass++; System.out.println("    PASS  " + label + " = " + actual);
        } else {
            fail++; System.out.println("    FAIL  " + label
                    + " : expected <" + expected + "> got <" + actual + ">");
        }
    }

    public static void main(String[] args) {
        System.out.println("=== a real file, exactly as LRCLIB writes it ===");
        // Copied from what landed on the device: note the space after the bracket.
        LrcParser.Lyrics l = LrcParser.parse(
                "[00:09.92] Suzanne takes you down\n"
              + "[00:13.53] To her place near the river\n"
              + "[00:17.48] You can hear the boats go by\n"
              + "[00:20.47] You can spend the night beside her\n");
        check("four lines", l.lines.size(), 4);
        check("it knows it is timed", l.synced, true);
        check("first time is 9.92s", l.lines.get(0).timeMs, 9920L);
        check("the leading space is gone", l.lines.get(0).text, "Suzanne takes you down");

        System.out.println("\n=== finding the line being sung ===");
        check("before the first line, nothing is lit", l.indexAt(0), -1);
        check("a moment before the first", l.indexAt(9919), -1);
        check("exactly on the first", l.indexAt(9920), 0);
        check("between the first and second", l.indexAt(12000), 0);
        check("on the second", l.indexAt(13530), 1);
        check("past the last, the last stays lit", l.indexAt(999999), 3);

        System.out.println("\n=== a chorus written once and sung four times ===");
        LrcParser.Lyrics ch = LrcParser.parse(
                "[00:10.00][01:20.00][02:30.00][03:40.00]We all live in a yellow submarine\n"
              + "[00:15.00]A verse\n");
        check("one written line becomes four", ch.lines.size(), 5);
        check("and they are in time order", ch.lines.get(1).timeMs, 15000L);
        check("the chorus is lit at 1:20", ch.lines.get(ch.indexAt(80000)).text,
                "We all live in a yellow submarine");

        System.out.println("\n=== the tags people put at the top ===");
        LrcParser.Lyrics m = LrcParser.parse(
                "[ar:Leonard Cohen]\n[ti:Suzanne]\n[al:The Best Of]\n[by:someone]\n"
              + "[00:09.92]Suzanne takes you down\n");
        check("metadata is not sung", m.lines.size(), 1);
        check("the words survived", m.lines.get(0).text, "Suzanne takes you down");

        System.out.println("\n=== offset shifts every line ===");
        LrcParser.Lyrics off = LrcParser.parse("[offset:+500]\n[00:10.00]Late\n");
        check("a positive offset pulls the line earlier", off.lines.get(0).timeMs, 9500L);
        LrcParser.Lyrics neg = LrcParser.parse("[offset:-500]\n[00:10.00]Early\n");
        check("a negative offset pushes it later", neg.lines.get(0).timeMs, 10500L);
        LrcParser.Lyrics big = LrcParser.parse("[offset:+99000]\n[00:10.00]Silly\n");
        check("it never goes below zero", big.lines.get(0).timeMs, 0L);

        System.out.println("\n=== timestamp shapes seen in the wild ===");
        check("mm:ss", LrcParser.parse("[01:02]x").lines.get(0).timeMs, 62000L);
        check("mm:ss.xx hundredths", LrcParser.parse("[01:02.50]x").lines.get(0).timeMs, 62500L);
        check("mm:ss.xxx milliseconds", LrcParser.parse("[01:02.500]x").lines.get(0).timeMs, 62500L);
        check("mm:ss:xx, the old colon form", LrcParser.parse("[01:02:50]x").lines.get(0).timeMs, 62500L);
        check("minutes past 60 still work", LrcParser.parse("[75:00.00]x").lines.get(0).timeMs, 4500000L);

        System.out.println("\n=== lyrics with no timings at all ===");
        LrcParser.Lyrics plain = LrcParser.parse(
                "Suzanne takes you down\n\nTo her place near the river\n");
        check("still worth showing", plain.lines.size(), 2);
        check("but it says it cannot follow along", plain.synced, false);
        check("and asking which line is being sung gives nothing", plain.indexAt(50000), -1);
        check("blank lines are dropped", plain.lines.get(1).text, "To her place near the river");

        System.out.println("\n=== the mess real files arrive in ===");
        check("CRLF", LrcParser.parse("[00:01.00]a\r\n[00:02.00]b\r\n").lines.size(), 2);
        check("a byte-order mark", LrcParser.parse("﻿[00:01.00]a\n").lines.get(0).text, "a");
        check("empty file", LrcParser.parse("").lines.size(), 0);
        check("null", LrcParser.parse(null).lines.size(), 0);
        check("nothing but metadata", LrcParser.parse("[ar:x]\n[ti:y]\n").lines.size(), 0);
        check("an unclosed bracket is words, not a time",
                LrcParser.parse("[00:01.00 broken\n").lines.get(0).text, "[00:01.00 broken");
        check("a timed line with no words is kept - it is a gap in the singing",
                LrcParser.parse("[00:01.00]\n[00:05.00]words\n").lines.size(), 2);
        check("out-of-order timestamps are sorted",
                LrcParser.parse("[00:20.00]b\n[00:10.00]a\n").lines.get(0).text, "a");
        check("60 seconds is not a valid second, so it is words",
                LrcParser.parse("[00:60.00]x\n").synced, false);

        System.out.println("\n=== keeping the sung line in the middle ===");
        final int VIEW = 200;                       // a window 200 skin px tall
        // With nothing sung yet the list sits at the top rather than jumping about.
        check("before the song starts", LyricsGeometry.centredScroll(-1, VIEW), 0);
        // Line 0 tall and centred: its middle should land on the middle of the window.
        int s0 = LyricsGeometry.centredScroll(0, VIEW);
        int mid0 = LyricsGeometry.topOf(0, 0) + LyricsGeometry.heightOf(0, 0) / 2;
        check("line 0 sits dead centre", mid0 - s0, VIEW / 2);
        int s9 = LyricsGeometry.centredScroll(9, VIEW);
        int mid9 = LyricsGeometry.topOf(9, 9) + LyricsGeometry.heightOf(9, 9) / 2;
        check("and so does line 9", mid9 - s9, VIEW / 2);
        check("the early lines hang above the top edge, as they should", s0 < 0, true);

        System.out.println("\n=== the sung line is the only tall one ===");
        check("an ordinary line", LyricsGeometry.heightOf(3, 7), LyricsGeometry.LINE_H);
        check("the sung one is double plus air", LyricsGeometry.heightOf(7, 7),
                LyricsGeometry.CURRENT_H + 2 * LyricsGeometry.CURRENT_PAD);
        check("lines above it are not pushed down",
                LyricsGeometry.topOf(3, 7), 3 * LyricsGeometry.LINE_H);
        check("lines below it are",
                LyricsGeometry.topOf(8, 7) - LyricsGeometry.topOf(7, 7),
                LyricsGeometry.heightOf(7, 7));
        // topOf must agree with adding the heights up one at a time, or the list drifts.
        int walk = 0;
        boolean agrees = true;
        for (int i = 0; i < 40; i++) {
            if (LyricsGeometry.topOf(i, 7) != walk) agrees = false;
            walk += LyricsGeometry.heightOf(i, 7);
        }
        check("topOf agrees with stacking them by hand", agrees, true);
        check("and with the total", LyricsGeometry.totalHeight(40, 7), walk);

        System.out.println("\n=== the scroll eases, and it settles ===");
        int at = 0, steps = 0;
        while (at != 500 && steps < 500) { at = LyricsGeometry.easeScroll(at, 500, 3); steps++; }
        check("it arrives exactly", at, 500);
        check("and reasonably quickly", steps < 60, true);
        int back = 500, bsteps = 0;
        while (back != 0 && bsteps < 500) { back = LyricsGeometry.easeScroll(back, 0, 3); bsteps++; }
        check("it comes back up too", back, 0);
        check("no movement when already there", LyricsGeometry.easeScroll(42, 42, 3), 42);
        check("it never overshoots", LyricsGeometry.easeScroll(499, 500, 3) <= 500, true);

        System.out.println("\n=== only the lines on screen get drawn ===");
        int scroll = LyricsGeometry.centredScroll(20, VIEW);
        int first = LyricsGeometry.firstVisible(scroll, 20, 100);
        int last = LyricsGeometry.lastVisible(scroll, VIEW, 20, 100);
        check("the sung line is in the visible range", first <= 20 && 20 < last, true);
        check("and it is a handful of lines, not all hundred", last - first < 30, true);

        System.out.println("\n================================");
        System.out.println("  " + pass + " passed, " + fail + " failed");
        System.out.println("================================");
        if (fail > 0) System.exit(1);
    }
}
