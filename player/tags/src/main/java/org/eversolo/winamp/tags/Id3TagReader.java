package org.eversolo.winamp.tags;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Reads ID3v2 tags from MP3 files. Handles v2.2, v2.3 and v2.4.
 *
 * Duration is NOT available from ID3 - it lives in the audio frames. On Android the
 * scanner supplements it with MediaMetadataRetriever, which does return duration
 * correctly on this device even though it fails to return FLAC tags.
 */
public final class Id3TagReader implements TagReader {

    private final boolean readArtwork;
    private static final int MAX_TAG = 16 * 1024 * 1024;

    public Id3TagReader(boolean readArtwork) {
        this.readArtwork = readArtwork;
    }

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        String n = fileName.toLowerCase();
        return n.endsWith(".mp3") || n.endsWith(".aac");
    }

    @Override
    public TrackTags read(File file) {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file), 64 * 1024))) {

            byte[] head = new byte[10];
            in.readFully(head);
            if (head[0] != 'I' || head[1] != 'D' || head[2] != '3') return null;

            int major = head[3] & 0xFF;
            int flags = head[5] & 0xFF;
            int size = synchsafe(head, 6);
            if (size <= 0 || size > MAX_TAG) return null;

            byte[] body = new byte[size];
            in.readFully(body);

            TrackTags t = new TrackTags();
            t.source = "id3v2." + major;

            int p = 0;
            // Extended header (v2.3/v2.4) - skip it.
            if ((flags & 0x40) != 0 && p + 4 <= size) {
                int extLen = (major >= 4) ? synchsafe(body, p) : be32(body, p) + 4;
                if (extLen > 0 && p + extLen <= size) p += extLen;
            }

            int idLen = (major == 2) ? 3 : 4;
            int sizeLen = (major == 2) ? 3 : 4;
            int flagLen = (major == 2) ? 0 : 2;

            while (p + idLen + sizeLen + flagLen <= size) {
                String id = new String(body, p, idLen, StandardCharsets.ISO_8859_1);
                if (id.charAt(0) == 0) break;                  // padding
                p += idLen;

                int frameSize;
                if (major == 2) {
                    frameSize = ((body[p] & 0xFF) << 16) | ((body[p + 1] & 0xFF) << 8) | (body[p + 2] & 0xFF);
                } else if (major >= 4) {
                    frameSize = synchsafe(body, p);
                } else {
                    frameSize = be32(body, p);
                }
                p += sizeLen + flagLen;

                if (frameSize <= 0 || p + frameSize > size) break;

                handleFrame(id, body, p, frameSize, t);
                p += frameSize;
            }
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    private void handleFrame(String id, byte[] b, int off, int len, TrackTags t) {
        switch (id) {
            case "TIT2": case "TT2": t.title = text(b, off, len); break;
            case "TPE1": case "TP1": t.artist = text(b, off, len); break;
            case "TALB": case "TAL": t.album = text(b, off, len); break;
            case "TPE2": case "TP2": t.albumArtist = text(b, off, len); break;
            case "TCON": case "TCO": t.genre = cleanGenre(text(b, off, len)); break;
            case "TRCK": case "TRK": t.trackNumber = TrackTags.parseNumber(text(b, off, len)); break;
            case "TPOS": case "TPA": t.discNumber = TrackTags.parseNumber(text(b, off, len)); break;
            case "TYER": case "TYE": case "TDRC": case "TDAT":
                if (t.year == null) t.year = TrackTags.parseYear(text(b, off, len));
                break;
            case "APIC": case "PIC":
                if (readArtwork && t.artwork == null) t.artwork = picture(id, b, off, len);
                break;
            default: break;
        }
    }

    /** Text frames: 1 encoding byte, then the string. */
    private static String text(byte[] b, int off, int len) {
        if (len < 1) return null;
        int enc = b[off] & 0xFF;
        int start = off + 1;
        int n = len - 1;
        if (n <= 0) return null;
        String s = new String(b, start, n, charsetFor(enc));
        // Strip trailing nulls and any BOM the decoder left behind.
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == 0 || s.charAt(end - 1) == '﻿')) end--;
        s = s.substring(0, end).replace("﻿", "").trim();
        return s.isEmpty() ? null : s;
    }

    private static Charset charsetFor(int enc) {
        switch (enc) {
            case 1: return StandardCharsets.UTF_16;     // with BOM
            case 2: return StandardCharsets.UTF_16BE;
            case 3: return StandardCharsets.UTF_8;
            default: return StandardCharsets.ISO_8859_1;
        }
    }

    /** "(17)Rock" and "(17)" are legal ID3v1-style genre references. */
    private static String cleanGenre(String g) {
        if (g == null) return null;
        String s = g.trim();
        while (s.startsWith("(")) {
            int close = s.indexOf(')');
            if (close < 0) break;
            String rest = s.substring(close + 1).trim();
            if (rest.isEmpty()) return null;
            s = rest;
        }
        return s.isEmpty() ? null : s;
    }

    /**
     * APIC: encoding, MIME (null-terminated), picture type, description
     * (null-terminated in the frame's encoding), then the image bytes.
     * PIC (v2.2) uses a fixed 3-character image format instead of a MIME string.
     */
    private static byte[] picture(String id, byte[] b, int off, int len) {
        try {
            int p = off;
            int end = off + len;
            int enc = b[p] & 0xFF;
            p++;

            if ("PIC".equals(id)) {
                p += 3;                                   // "JPG" / "PNG"
            } else {
                while (p < end && b[p] != 0) p++;         // MIME
                p++;
            }
            if (p >= end) return null;
            p++;                                          // picture type

            // Description, terminated by 1 or 2 nulls depending on encoding.
            if (enc == 1 || enc == 2) {
                while (p + 1 < end && !(b[p] == 0 && b[p + 1] == 0)) p += 2;
                p += 2;
            } else {
                while (p < end && b[p] != 0) p++;
                p++;
            }
            if (p >= end) return null;

            byte[] art = new byte[end - p];
            System.arraycopy(b, p, art, 0, art.length);
            return art;
        } catch (Exception e) {
            return null;
        }
    }

    /** Synchsafe integers store 7 bits per byte, so a tag length can never look like a frame sync. */
    private static int synchsafe(byte[] b, int p) {
        return ((b[p] & 0x7F) << 21) | ((b[p + 1] & 0x7F) << 14)
                | ((b[p + 2] & 0x7F) << 7) | (b[p + 3] & 0x7F);
    }

    private static int be32(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }
}
