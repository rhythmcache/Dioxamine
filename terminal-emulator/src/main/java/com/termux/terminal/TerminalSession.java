package com.termux.terminal;

import java.util.UUID;

/**
 * An abstract terminal session decoupled from local PTY processes.
 * Exposes the surface required by {@link TerminalEmulator} and terminal views.
 */
public abstract class TerminalSession extends TerminalOutput {

    public final String mHandle = UUID.randomUUID().toString();
    public String mSessionName;

    private final byte[] mUtf8InputBuffer = new byte[5];

    /**
     * Get the emulator associated with this session.
     */
    public abstract TerminalEmulator getEmulator();

    /**
     * Update the window size in columns, rows, and cell pixel dimensions.
     */
    public abstract void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    /**
     * Update the client receiving session callbacks.
     */
    public abstract void updateTerminalSessionClient(TerminalSessionClient client);

    /**
     * Reset the terminal emulator state.
     */
    public abstract void reset();

    /**
     * Finish or close the session if running.
     */
    public abstract void finishIfRunning();

    /**
     * Whether the session is currently active.
     */
    public abstract boolean isRunning();

    /**
     * Exit status of the session if finished.
     */
    public abstract int getExitStatus();

    /**
     * Process ID or session identifier.
     */
    public abstract int getPid();

    /**
     * Working directory if available.
     */
    public abstract String getCwd();

    /**
     * Terminal title as set through escape sequences.
     */
    public abstract String getTitle();

    /**
     * Write the Unicode code point to the terminal encoded in UTF-8.
     */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }
}
