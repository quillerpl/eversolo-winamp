package org.eversolo.winamp.tags;

import java.io.File;

/** Reads metadata out of one audio file. Implementations must never throw. */
public interface TagReader {

    /** Cheap check on the filename before opening anything. */
    boolean supports(String fileName);

    /**
     * @return tags, or null if this reader could not make sense of the file.
     *         Must not throw - a corrupt file is normal in a real library.
     */
    TrackTags read(File file);
}
