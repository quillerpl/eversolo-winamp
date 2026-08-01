package org.eversolo.winamp.playback;

/** Immutable snapshot of what the device is doing. Published by the state poller. */
public final class PlaybackState {

    public enum Status { IDLE, PLAYING, PAUSED, UNKNOWN }

    public static final PlaybackState EMPTY =
            new PlaybackState(Status.IDLE, "", "", "", 0, 0, 0, 200, false, 0, 0, 0, 0, false);

    public final Status status;
    public final String title;
    public final String artist;
    public final String album;
    public final long positionMs;
    public final long durationMs;
    public final int volume;
    public final int maxVolume;
    public final boolean muted;

    /**
     * What the device says about the audio itself. It reports all four in getState -
     * sampleRateNumber, bitrate, bits and channels - which is exactly what the main
     * window's kbps / kHz / mono-stereo displays are for. Zero means "not reported".
     */
    public final int sampleRate;      // Hz, e.g. 44100
    public final int bitrateKbps;     // e.g. 128, or 1411 for CD-quality FLAC
    public final int bits;            // 16, 24, 32
    public final int channels;        // 1 = mono, 2 = stereo

    /**
     * The device's own answer to "can I have a spectrum?", from
     * everSoloPlayInfo.isHasSpectrum. On this unit it is false for everything - local
     * files and internet radio alike - which is why getSpectrum answers with {}.
     */
    public final boolean hasSpectrum;

    public PlaybackState(Status status, String title, String artist, String album,
                         long positionMs, long durationMs,
                         int volume, int maxVolume, boolean muted,
                         int sampleRate, int bitrateKbps, int bits, int channels,
                         boolean hasSpectrum) {
        this.status = status;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.volume = volume;
        this.maxVolume = maxVolume <= 0 ? 200 : maxVolume;
        this.muted = muted;
        this.sampleRate = sampleRate;
        this.bitrateKbps = bitrateKbps;
        this.bits = bits;
        this.channels = channels;
        this.hasSpectrum = hasSpectrum;
    }

    public boolean isPlaying() { return status == Status.PLAYING; }

    /** Device state codes: 0 = idle/stopped, 3 = playing, 4 = paused (API_FINDINGS.md §5). */
    public static Status fromDeviceState(int code) {
        switch (code) {
            case 0: return Status.IDLE;
            case 3: return Status.PLAYING;
            case 4: return Status.PAUSED;
            default: return Status.UNKNOWN;
        }
    }

    public String formattedPosition() { return mmss(positionMs); }
    public String formattedDuration() { return mmss(durationMs); }

    private static String mmss(long ms) {
        if (ms <= 0) return "0:00";
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
