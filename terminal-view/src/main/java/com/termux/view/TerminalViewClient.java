package com.termux.view;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.termux.terminal.TerminalSession;

/**
 * The interface for communication between {@link TerminalView} and its client. It allows for getting
 * various  configuration options from the client and for sending back data to the client like logs,
 * key events, both hardware and IME (which makes it different from that available with
 * {@link View#setOnKeyListener(View.OnKeyListener)}, etc. It must be set for the
 * {@link TerminalView} through {@link TerminalView#setTerminalViewClient(TerminalViewClient)}.
 */
public interface TerminalViewClient {

    default float onScale(float scale) {
        return 1.0f;
    }

    default void onSingleTapUp(MotionEvent e) {}

    default boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    default boolean shouldEnforceCharBasedInput() {
        return false;
    }

    default boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    default boolean isTerminalViewSelected() {
        return true;
    }

    default void copyModeChanged(boolean copyMode) {}

    default boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        return false;
    }

    default boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    default boolean onLongPress(MotionEvent event) {
        return false;
    }

    default boolean readControlKey() {
        return false;
    }

    default boolean readAltKey() {
        return false;
    }

    default boolean readShiftKey() {
        return false;
    }

    default boolean readFnKey() {
        return false;
    }

    default boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    default void onEmulatorSet() {}

    default void logError(String tag, String message) {}

    default void logWarn(String tag, String message) {}

    default void logInfo(String tag, String message) {}

    default void logDebug(String tag, String message) {}

    default void logVerbose(String tag, String message) {}

    default void logStackTraceWithMessage(String tag, String message, Exception e) {}

    default void logStackTrace(String tag, Exception e) {}

}
