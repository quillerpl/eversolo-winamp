package org.eversolo.winamp.playback;

/**
 * How the app makes sound. One implementation today (the Eversolo's own engine over
 * loopback HTTP), but kept behind this interface because decision D4 anticipates two
 * plausible changes: the gapless optimisation (D7), and the possibility of the app
 * playing audio itself. Neither should be visible to the library, playlist or UI.
 */
public interface PlaybackEngine {

    interface Listener {
        void onState(PlaybackState state);
    }

    /**
     * Start this exact file.
     *
     * @return false if the device did not actually start it. This returns a boolean
     *         rather than void on purpose: openFile answers HTTP 200 for files it
     *         silently refuses to play (.m3u, .cue - API_FINDINGS.md §2), so the result
     *         must be confirmed against getState, never assumed.
     */
    boolean play(String absolutePath, String expectedTitle);

    void pause();
    void resume();
    void togglePlayPause();
    void next();
    void previous();
    void seekTo(long ms);

    /** 0..200 on this device, where 200 is 0 dB / full output - not a percentage. */
    void setVolume(int volume);

    PlaybackState state();

    void start();
    void stop();

    void addListener(Listener l);
    void removeListener(Listener l);
}
