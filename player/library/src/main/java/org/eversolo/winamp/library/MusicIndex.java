package org.eversolo.winamp.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Groups scanned tracks into the artist -> album -> track shape the browser needs. */
public final class MusicIndex {

    public static final class Album {
        public final String name;
        public final String artist;
        public final Integer year;
        public final List<Track> tracks = new ArrayList<>();

        Album(String name, String artist, Integer year) {
            this.name = name; this.artist = artist; this.year = year;
        }

        public long totalDurationMs() {
            long total = 0;
            for (Track t : tracks) total += t.durationMs;
            return total;
        }

        /** Best available quality label across the album, for the browser list. */
        public String qualityLabel() {
            return tracks.isEmpty() ? "" : tracks.get(0).qualityLabel();
        }
    }

    private final List<Track> all;
    private final Map<String, List<Album>> albumsByArtist = new LinkedHashMap<>();
    private final List<String> artists = new ArrayList<>();

    public MusicIndex(List<Track> tracks) {
        this.all = tracks;

        // artist -> album name -> album
        Map<String, Map<String, Album>> grouped =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (Track t : tracks) {
            String artist = t.filingArtist();
            Map<String, Album> albums = grouped.get(artist);
            if (albums == null) {
                albums = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                grouped.put(artist, albums);
            }
            Album a = albums.get(t.album);
            if (a == null) {
                a = new Album(t.album, artist, t.year);
                albums.put(t.album, a);
            }
            a.tracks.add(t);
        }

        Comparator<Track> trackOrder = (x, y) -> {
            int dx = x.discNumber == null ? 1 : x.discNumber;
            int dy = y.discNumber == null ? 1 : y.discNumber;
            if (dx != dy) return Integer.compare(dx, dy);
            int tx = x.trackNumber == null ? Integer.MAX_VALUE : x.trackNumber;
            int ty = y.trackNumber == null ? Integer.MAX_VALUE : y.trackNumber;
            if (tx != ty) return Integer.compare(tx, ty);
            return x.fileName.compareToIgnoreCase(y.fileName);
        };

        for (Map.Entry<String, Map<String, Album>> e : grouped.entrySet()) {
            List<Album> list = new ArrayList<>(e.getValue().values());
            for (Album a : list) Collections.sort(a.tracks, trackOrder);
            Collections.sort(list, (x, y) -> {
                if (x.year != null && y.year != null && !x.year.equals(y.year)) {
                    return Integer.compare(x.year, y.year);
                }
                return x.name.compareToIgnoreCase(y.name);
            });
            albumsByArtist.put(e.getKey(), list);
            artists.add(e.getKey());
        }
    }

    public List<String> artists() { return artists; }

    public List<Album> albumsOf(String artist) {
        List<Album> a = albumsByArtist.get(artist);
        return a == null ? Collections.<Album>emptyList() : a;
    }

    public List<Track> allTracks() { return all; }

    public int albumCount() {
        int n = 0;
        for (List<Album> a : albumsByArtist.values()) n += a.size();
        return n;
    }
}
