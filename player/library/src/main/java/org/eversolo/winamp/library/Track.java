package org.eversolo.winamp.library;

import org.eversolo.winamp.tags.TrackTags;

import java.io.File;

/**
 * One playable file.
 *
 * absolutePath is the identity of a track. NOT any device-side id: the Eversolo's library
 * ids are only reachable through a search capped at 30 results, and its play-queue ids are
 * hashes that differ from the database ids for the same track (API_FINDINGS.md §5). The
 * path is the only stable, complete identifier - and it is exactly what openFile consumes.
 */
public final class Track {

    public final String absolutePath;
    public final String fileName;
    public final String extension;
    public final long fileSize;

    public final String title;
    public final String artist;
    public final String album;
    public final String albumArtist;
    public final String genre;
    public final Integer trackNumber;
    public final Integer discNumber;
    public final Integer year;

    public final long durationMs;
    public final int sampleRate;
    public final int bitDepth;
    public final int channels;

    public final String tagSource;

    public Track(File file, TrackTags t) {
        this.absolutePath = file.getAbsolutePath();
        this.fileName = file.getName();
        int dot = fileName.lastIndexOf('.');
        this.extension = dot > 0 ? fileName.substring(dot + 1).toLowerCase() : "";
        this.fileSize = file.length();

        this.title = firstNonBlank(t.title, stripExtension(fileName));
        this.artist = firstNonBlank(t.artist, "Unknown Artist");
        this.album = firstNonBlank(t.album, "Unknown Album");
        this.albumArtist = firstNonBlank(t.albumArtist, this.artist);
        this.genre = t.genre;
        this.trackNumber = t.trackNumber;
        this.discNumber = t.discNumber;
        this.year = t.year;

        this.durationMs = t.durationMs;
        this.sampleRate = t.sampleRate;
        this.bitDepth = t.bitDepth;
        this.channels = t.channels;
        this.tagSource = t.source;
    }

    /** The artist an album should be filed under. */
    public String filingArtist() {
        return albumArtist != null ? albumArtist : artist;
    }

    public boolean isHiRes() {
        return sampleRate > 48000 || bitDepth > 16;
    }

    /** "3:45", or "" when the duration is unknown (MP3 tags do not carry it). */
    public String formattedDuration() {
        if (durationMs <= 0) return "";
        long total = durationMs / 1000;
        return String.format("%d:%02d", total / 60, total % 60);
    }

    /** "FLAC 24/96" style quality summary for the UI. */
    public String qualityLabel() {
        StringBuilder sb = new StringBuilder(extension.toUpperCase());
        if (bitDepth > 0 && sampleRate > 0) {
            sb.append(' ').append(bitDepth).append('/');
            if (sampleRate % 1000 == 0) sb.append(sampleRate / 1000);
            else sb.append(String.format("%.1f", sampleRate / 1000.0));
        }
        return sb.toString();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.trim().isEmpty()) ? a.trim() : b;
    }

    private static String stripExtension(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    @Override
    public String toString() {
        return artist + " - " + album + " - " + title;
    }
}
