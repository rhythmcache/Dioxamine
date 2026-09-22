package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The interface for communication between {@link TerminalSession} and its client. It is used to
 * send callbacks to the client when {@link TerminalSession} changes or for sending other
 * back data to the client like logs.
 */
public interface TerminalSessionClient {

    void onTextChanged(@NonNull AdbTerminalSession changedSession);

    void onTitleChanged(@NonNull AdbTerminalSession changedSession);

    void onSessionFinished(@NonNull AdbTerminalSession finishedSession);

    void onCopyTextToClipboard(@NonNull AdbTerminalSession session, String text);

    void onPasteTextFromClipboard(@Nullable AdbTerminalSession session);

    void onBell(@NonNull AdbTerminalSession session);

    void onColorsChanged(@NonNull AdbTerminalSession session);

    void onTerminalCursorStateChange(boolean state);

    void setTerminalShellPid(@NonNull AdbTerminalSession session, int pid);



    Integer getTerminalCursorStyle();



    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
