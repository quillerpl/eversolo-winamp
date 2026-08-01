package org.eversolo.winamp.tags;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads FLAC metadata directly out of the file.
 *
 * This exists because Android cannot do it on the DMP-A6: MediaMetadataRetriever
 * returns null for artist/album/title on FLAC (duration works), and MediaStore
 * reports "&lt;unknown&gt;". Since the library is overwhelmingly FLAC, this class is
 * load-bearing. See ANSWERS_Q1_Q7.md Q1b.
 *
 * Format (https://xiph.org/flac/format.html):
 *   "fLaC" marker, then a chain of metadata blocks. Each block is
 *     1 byte  : bit7 = last-block flag, bits 0..6 = block type
 *     3 bytes : big-endian length of the block body
 *   We care about STREAMINFO (0), VORBIS_COMMENT (4) and PICTURE (6).
 *
 * Note VORBIS_COMMENT is little-endian while the rest of FLAC is big-endian.
 * That is a genuine quirk of the format, not a bug here.
 */
public final class FlacTagReader implements TagReader {

    private static final int STREAMINFO = 0;
    private static final int VORBIS_COMMENT = 4;
    private static final int PICTURE = 6;

    /** Artwork is large; skip it during a full library scan and fetch it on demand. */
    private final boolean readArtwork;

    /** Refuse absurd blocks rather than allocating whatever the file claims. */
    private static final int MAX_BLOCK = 32 * 1024 * 1024;

    public FlacTagReader(boolean readArtwork) {
        this.readArtwork = readArtwork;
    }

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".flac");
    }

    @Override
    public TrackTags read(File file) {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file), 64 * 1024))) {

            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') {
                return null;   // not FLAC (could be FLAC-in-Ogg, which we do not handle)
            }

            TrackTags t = new TrackTags();
            t.source = "flac";

            boolean last = false;
            while (!last) {
                int header = in.read();
                if (header < 0) break;
                last = (header & 0x80) != 0;
                int type = header & 0x7F;
                int len = (in.read() << 16) | (in.read() << 8) | in.read();
                if (len < 0 || len > MAX_BLOCK) break;

                switch (type) {
                    case STREAMINFO:
                        readStreamInfo(in, len, t);
                        break;
                    case VORBIS_COMMENT:
                        readVorbisComment(in, len, t);
                        break;
                    case PICTURE:
                        if (readArtwork) readPicture(in, len, t);
                        else skipFully(in, len);
                        break;
                    default:
                        skipFully(in, len);
                }
            }
            return t;
        } catch (Exception e) {
            return null;   // corrupt or truncated file - normal in a real library
        }
    }

    /**
     * STREAMINFO is 34 bytes. Bytes 10..17 pack, as one 64-bit big-endian value:
     *   20 bits sample rate | 3 bits (channels-1) | 5 bits (bitsPerSample-1) | 36 bits total samples
     */
    private void readStreamInfo(DataInputStream in, int len, TrackTags t) throws IOException {
        byte[] si = new byte[len];
        in.readFully(si);
        if (len < 18) return;

        long v = 0;
        for (int i = 10; i < 18; i++) v = (v << 8) | (si[i] & 0xFFL);

        t.sampleRate = (int) ((v >>> 44) & 0xFFFFF);
        t.channels = (int) (((v >>> 41) & 0x7) + 1);
        t.bitDepth = (int) (((v >>> 36) & 0x1F) + 1);
        long totalSamples = v & 0xFFFFFFFFFL;

        if (t.sampleRate > 0 && totalSamples > 0) {
            t.durationMs = totalSamples * 1000L / t.sampleRate;
        }
    }

    /** VORBIS_COMMENT: little-endian lengths, entries of the form KEY=value in UTF-8. */
    private void readVorbisComment(DataInputStream in, int len, TrackTags t) throws IOException {
        byte[] b = new byte[len];
        in.readFully(b);

        int p = 0;
        if (p + 4 > len) return;
        int vendorLen = le32(b, p);
        p += 4;
        if (vendorLen < 0 || p + vendorLen > len) return;
        p += vendorLen;

        if (p + 4 > len) return;
        int count = le32(b, p);
        p += 4;
        if (count < 0 || count > 10000) return;

        for (int i = 0; i < count; i++) {
            if (p + 4 > len) return;
            int l = le32(b, p);
            p += 4;
            if (l < 0 || p + l > len) return;
            String entry = new String(b, p, l, StandardCharsets.UTF_8);
            p += l;

            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String key = entry.substring(0, eq).toUpperCase();
            String val = entry.substring(eq + 1);
            if (val.trim().isEmpty()) continue;

            switch (key) {
                case "TITLE":                t.title = val; break;
                case "ARTIST":               t.artist = val; break;
                case "ALBUM":                t.album = val; break;
                case "ALBUMARTIST":
                case "ALBUM ARTIST":         t.albumArtist = val; break;
                case "GENRE":                t.genre = val; break;
                case "TRACKNUMBER":          t.trackNumber = TrackTags.parseNumber(val); break;
                case "DISCNUMBER":           t.discNumber = TrackTags.parseNumber(val); break;
                case "DATE":
                case "YEAR":
                case "ORIGINALDATE":
                    if (t.year == null) t.year = TrackTags.parseYear(val);
                    break;
                default: break;
            }
        }
    }

    /** PICTURE block, big-endian. We only want the image bytes. */
    private void readPicture(DataInputStream in, int len, TrackTags t) throws IOException {
        byte[] b = new byte[len];
        in.readFully(b);

        int p = 0;
        if (p + 4 > len) return;
        p += 4;                               // picture type

        if (p + 4 > len) return;
        int mimeLen = be32(b, p); p += 4;
        if (mimeLen < 0 || p + mimeLen > len) return;
        p += mimeLen;

        if (p + 4 > len) return;
        int descLen = be32(b, p); p += 4;
        if (descLen < 0 || p + descLen > len) return;
        p += descLen;

        if (p + 16 > len) return;
        p += 16;                              // width, height, depth, colour count

        if (p + 4 > len) return;
        int dataLen = be32(b, p); p += 4;
        if (dataLen < 0 || p + dataLen > len) return;

        // Keep the first picture only; front cover is conventionally first.
        if (t.artwork == null) {
            byte[] art = new byte[dataLen];
            System.arraycopy(b, p, art, 0, dataLen);
            t.artwork = art;
        }
    }

    private static int le32(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8)
                | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }

    private static int be32(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    /** InputStream.skip is allowed to skip less than asked; loop until done. */
    static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) return;    // EOF
                left--;
            } else {
                left -= s;
            }
        }
    }
}
