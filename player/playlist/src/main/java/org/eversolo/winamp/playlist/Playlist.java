package org.eversolo.winamp.playlist;

import org.eversolo.winamp.library.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * The user's own ordered list of tracks, in whatever order they chose, across any folders.
 *
 * This has to live in the app (decision D1) because the device offers no queue
 * manipulation at all: every one of addToQueue, addPlayQueue, insertPlay, setPlayQueue
 * and friends returns "Method Not Allowed", and openFile replaces the device's queue
 * wholesale with the chosen track's containing folder (API_FINDINGS.md §6).
 */
public final class Playlist {

    public interface Listener {
        void onPlaylistChanged();
    }

    private final List<Track> items = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private int currentIndex = -1;

    public synchronized int size() { return items.size(); }
    public synchronized boolean isEmpty() { return items.isEmpty(); }

    public synchronized Track get(int i) {
        return (i >= 0 && i < items.size()) ? items.get(i) : null;
    }

    public synchronized List<Track> tracks() { return new ArrayList<>(items); }

    public synchronized int currentIndex() { return currentIndex; }

    public synchronized Track current() { return get(currentIndex); }

    public synchronized void setCurrentIndex(int i) {
        currentIndex = (i >= -1 && i < items.size()) ? i : -1;
        fire();
    }

    public synchronized void add(Track t) {
        if (t != null) { items.add(t); fire(); }
    }

    public synchronized void addAll(List<Track> ts) {
        if (ts != null && !ts.isEmpty()) { items.addAll(ts); fire(); }
    }

    public synchronized void insert(int index, Track t) {
        if (t == null) return;
        int i = Math.max(0, Math.min(index, items.size()));
        items.add(i, t);
        if (currentIndex >= i) currentIndex++;
        fire();
    }

    public synchronized void removeAt(int index) {
        if (index < 0 || index >= items.size()) return;
        items.remove(index);
        if (index < currentIndex) currentIndex--;
        else if (index == currentIndex) currentIndex = -1;
        fire();
    }

    public synchronized void move(int from, int to) {
        if (from < 0 || from >= items.size()) return;
        int t = Math.max(0, Math.min(to, items.size() - 1));
        Track moved = items.remove(from);
        items.add(t, moved);
        if (currentIndex == from) currentIndex = t;
        fire();
    }

    public synchronized void clear() {
        items.clear();
        currentIndex = -1;
        fire();
    }

    /** Shuffles, keeping whatever is playing at its position so the music does not jump. */
    public synchronized void shuffle() {
        Track playing = current();
        Collections.shuffle(items, new Random());
        if (playing != null) {
            int at = indexOfPath(playing.absolutePath);
            currentIndex = at;
        }
        fire();
    }

    /**
     * Reorders the list, keeping whatever is playing selected - the same courtesy shuffle
     * does. Winamp's SORT LIST button lands here.
     */
    public synchronized void sort(Comparator<Track> order) {
        Track playing = current();
        Collections.sort(items, order);
        if (playing != null) currentIndex = indexOfPath(playing.absolutePath);
        fire();
    }

    /** Winamp sorts by title; on a library of ripped albums, artist first is more useful. */
    public static Comparator<Track> byArtistThenTitle() {
        return new Comparator<Track>() {
            @Override public int compare(Track a, Track b) {
                int c = a.artist.compareToIgnoreCase(b.artist);
                if (c != 0) return c;
                c = a.album.compareToIgnoreCase(b.album);
                if (c != 0) return c;
                Integer an = a.trackNumber, bn = b.trackNumber;
                if (an != null && bn != null && !an.equals(bn)) return an - bn;
                return a.title.compareToIgnoreCase(b.title);
            }
        };
    }

    public synchronized int indexOfPath(String absolutePath) {
        if (absolutePath == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (absolutePath.equals(items.get(i).absolutePath)) return i;
        }
        return -1;
    }

    public synchronized long totalDurationMs() {
        long total = 0;
        for (Track t : items) total += t.durationMs;
        return total;
    }

    /**
     * Is the track after {@code index} simply the next file in the same folder?
     *
     * If so, the device's own queue will advance to it by itself and no openFile is
     * needed. That is the basis of the deferred gapless optimisation (D7) - not used
     * yet, but the playlist is the only place that can answer the question.
     */
    public synchronized boolean nextIsSameFolder(int index) {
        Track a = get(index), b = get(index + 1);
        if (a == null || b == null) return false;
        return folderOf(a.absolutePath).equals(folderOf(b.absolutePath));
    }

    private static String folderOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : path;
    }

    public void addListener(Listener l) {
        synchronized (listeners) { listeners.add(l); }
    }

    public void removeListener(Listener l) {
        synchronized (listeners) { listeners.remove(l); }
    }

    private void fire() {
        List<Listener> snapshot;
        synchronized (listeners) { snapshot = new ArrayList<>(listeners); }
        for (Listener l : snapshot) {
            try { l.onPlaylistChanged(); } catch (Exception ignored) {}
        }
    }
}
