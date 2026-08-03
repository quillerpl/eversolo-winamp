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
        final int VIEW = 200;
        // Ten ordinary lines with the fourth sung, and the sung one wrapped onto two rows -
        // which is the case that matters: heights differ, so nothing can be calculated.
        int[] h = new int[10];
        for (int i = 0; i < h.length; i++) h[i] = LyricsGeometry.LINE_H;
        h[3] = 2 * LyricsGeometry.BIG_LINE_H + 2 * LyricsGeometry.CURRENT_PAD;

        check("nothing sung yet: sit at the top", LyricsGeometry.centredScroll(h, -1, VIEW), 0);
        int s3 = LyricsGeometry.centredScroll(h, 3, VIEW);
        check("the sung line sits dead centre",
                LyricsGeometry.topOf(h, 3) + h[3] / 2 - s3, VIEW / 2);
        check("even when it is two rows tall", h[3] > LyricsGeometry.LINE_H * 2, true);
        check("the early lines hang above the top edge", s3 < 0, true);
        check("an index past the end is harmless",
                LyricsGeometry.centredScroll(h, 99, VIEW), 0);
        check("so is an empty song", LyricsGeometry.centredScroll(new int[0], 0, VIEW), 0);

        System.out.println("\n=== the running total has to agree with itself ===");
        int walk = 0;
        boolean agrees = true;
        for (int i = 0; i < h.length; i++) {
            if (LyricsGeometry.topOf(h, i) != walk) agrees = false;
            walk += h[i];
        }
        check("topOf agrees with stacking them by hand", agrees, true);
        check("and with the total", LyricsGeometry.totalHeight(h), walk);
        check("lines above the tall one are not pushed down",
                LyricsGeometry.topOf(h, 2), 2 * LyricsGeometry.LINE_H);
        check("lines below it are", LyricsGeometry.topOf(h, 4) - LyricsGeometry.topOf(h, 3), h[3]);

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
        int first = LyricsGeometry.firstVisible(h, s3);
        int last = LyricsGeometry.lastVisible(h, s3, VIEW);
        check("the sung line is in the visible range", first <= 3 && 3 < last, true);
        check("a scroll above the start still begins at line 0",
                LyricsGeometry.firstVisible(h, -500), 0);

        System.out.println("\n================================");
        System.out.println("  " + pass + " passed, " + fail + " failed");
        System.out.println("================================");
        if (fail > 0) System.exit(1);
    }
}
