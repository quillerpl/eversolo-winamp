package org.eversolo.winamp.tags;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks the right reader for a file and fills any gaps from the file path.
 *
 * Order matters: real tags always win, path-derived values only ever fill holes.
 */
public final class TagReaders {

    public static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "flac", "mp3", "wav", "dsf", "dff", "m4a", "aac", "ape", "ogg", "opus",
            "aiff", "aif", "wv", "alac"
    ));

    private final List<TagReader> readers = new ArrayList<>();
    private final PathTagReader pathReader;

    public TagReaders(Set<String> scanRoots, boolean readArtwork) {
        readers.add(new FlacTagReader(readArtwork));
        readers.add(new Id3TagReader(readArtwork));
        pathReader = new PathTagReader(scanRoots);
    }

    public static boolean isAudioFile(String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return AUDIO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    /** Never returns null: worst case you get path-derived tags. */
    public TrackTags read(File file) {
        String name = file.getName();
        TrackTags tags = null;

        for (TagReader r : readers) {
            if (!r.supports(name)) continue;
            tags = r.read(file);
            if (tags != null && tags.hasCoreTags()) break;
            // A reader that opened the file but found no tags is still useful:
            // FLAC STREAMINFO gives us sample rate and duration. Keep it and fill gaps.
        }

        TrackTags fromPath = pathReader.read(file);
        if (tags == null) {
            if (fromPath != null) fromPath.source = "path";
            return fromPath != null ? fromPath : emptyFor(file);
        }
        tags.fillGapsFrom(fromPath);
        return tags;
    }

    private static TrackTags emptyFor(File f) {
        TrackTags t = new TrackTags();
        t.source = "none";
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        t.title = dot > 0 ? n.substring(0, dot) : n;
        return t;
    }
}
