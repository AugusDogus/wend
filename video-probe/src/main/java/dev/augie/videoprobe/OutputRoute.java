package dev.augie.videoprobe;

import java.util.function.BooleanSupplier;

/** Decoder-owner-thread routing policy. Generic only so lifecycle behavior can be tested without Android. */
final class OutputRoute<T> {
    interface DecoderOutput<T> { void switchTo(T surface); }
    enum Result { ATTACHED, NOT_PLAYING, SWITCH_FAILED }
    private final T original;
    private final DecoderOutput<T> decoder;
    private final BooleanSupplier playing;
    private Object owner;

    OutputRoute(T original, DecoderOutput<T> decoder, BooleanSupplier playing) {
        this.original = original; this.decoder = decoder; this.playing = playing;
    }

    Result attach(Object owner, T destination) {
        if (!playing.getAsBoolean()) return Result.NOT_PLAYING;
        try { decoder.switchTo(destination); }
        catch (IllegalStateException | IllegalArgumentException error) { return Result.SWITCH_FAILED; }
        this.owner = owner;
        return Result.ATTACHED;
    }

    boolean detach(Object owner) {
        if (this.owner == null || !this.owner.equals(owner)) return false;
        decoder.switchTo(original);
        this.owner = null;
        return true;
    }
}
