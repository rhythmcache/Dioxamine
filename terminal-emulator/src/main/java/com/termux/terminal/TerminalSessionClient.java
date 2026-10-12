package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The interface for communication between {@link TerminalSession} and its client. It is used to
 * send callbacks to the client when {@link TerminalSession} changes or for sending other
 * back data to the client like logs.
 */
public interface TerminalSessionClient {

    default void onTextChanged(@NonNull TerminalSession changedSession) {}

    default void onTitleChanged(@NonNull TerminalSession changedSession) {}

    default void onSessionFinished(@NonNull TerminalSession finishedSession) {}

    default void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {}

    default void onPasteTextFromClipboard(@Nullable TerminalSession session) {}

    default void onBell(@NonNull TerminalSession session) {}

    default void onColorsChanged(@NonNull TerminalSession session) {}

    default void onTerminalCursorStateChange(boolean state) {}

    default void setTerminalShellPid(@NonNull TerminalSession session, int pid) {}

    default Integer getTerminalCursorStyle() {
        return null;
    }

    default void logError(String tag, String message) {}

    default void logWarn(String tag, String message) {}

    default void logInfo(String tag, String message) {}

    default void logDebug(String tag, String message) {}

    default void logVerbose(String tag, String message) {}

    default void logStackTraceWithMessage(String tag, String message, Exception e) {}

    default void logStackTrace(String tag, Exception e) {}

}
