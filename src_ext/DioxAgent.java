import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Looper;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
import android.util.DisplayMetrics;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 *   --icons     dumps full package list + optional icons to stdout,.
 *   --daemon    Socket daemon mode binds localabstract:diox_agent and serves
 *               process-manager commands (list_processes, get_icon, force_stop, kill_pid)
 *               to a persistent Kotlin client, with a 60s idle auto-shutdown.
 *
 * Runs unprivileged, as shell UID, via `app_process`. No root assumed anywhere in
 * this file see per command notes on what that does and doesn't permit.
 */
public class DioxAgent {

    static final int ICON_SIZE = 48;
    static final String SELF_PATH = "/data/local/tmp/diox-agent.jar";
    static final String SOCKET_NAME = "diox_agent";
    static final long IDLE_TIMEOUT_MS = 60_000L;

    // command ids (client -> server, 1 byte) 
    static final int CMD_LIST_PROCESSES = 0x01;
    static final int CMD_GET_ICON = 0x02;
    static final int CMD_FORCE_STOP = 0x03;
    static final int CMD_KILL_PID = 0x04;

    // response status codes (server -> client, 1 byte, first byte of every reply) 
    static final int STATUS_OK = 0;
    static final int STATUS_ERROR = 1;

    // Shared helpers (used by both modes)
    static byte[] renderIconPng(Drawable d) {
        if (d == null) return new byte[0];
        try {
            Bitmap bmp = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            d.setBounds(0, 0, ICON_SIZE, ICON_SIZE);
            d.draw(canvas);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
            bmp.recycle();
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    static Resources getAppResources(String apkPath, String[] splitDirs) {
        try {
            AssetManager assetManager = AssetManager.class.newInstance();
            Method addAssetPathMethod = AssetManager.class.getMethod("addAssetPath", String.class);
            addAssetPathMethod.invoke(assetManager, apkPath);
            if (splitDirs != null) {
                for (String split : splitDirs) {
                    if (split != null && !split.isEmpty()) {
                        addAssetPathMethod.invoke(assetManager, split);
                    }
                }
            }
            DisplayMetrics displayMetrics = new DisplayMetrics();
            displayMetrics.setToDefaults();
            Configuration configuration = new Configuration();
            configuration.setToDefaults();
            return new Resources(assetManager, displayMetrics, configuration);
        } catch (Exception e) {
            return null;
        }
    }

    static void selfDestruct() {
        try {
            File self = new File(SELF_PATH);
            if (self.exists()) {
                self.delete();
            }
        } catch (Exception e) {
            // continue even if delete fails
            // already deleted, race with something else touching the file
        }
    }


    // Entry point
    public static void main(String[] args) throws Exception {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }

        boolean daemonMode = args.length > 0 && args[0].equals("--daemon");

        if (daemonMode) {
            selfDestruct(); // still drop the launcher jar even in daemon mode
            new DaemonServer().run();
        } else {
            // legacy CLI path, unchanged behavior
            selfDestruct();
            boolean includeIcons = args.length > 0 && args[0].equals("--icons");
            runLegacyDump(includeIcons);
        }
    }

    static void runLegacyDump(boolean includeIcons) throws Exception {
        Context systemContext = ActivityThread.systemMain().getSystemContext();
        PackageManager pm = systemContext.getPackageManager();

        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES);

        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(System.out));
        out.writeBytes("PKGD");
        out.writeByte(1);
        out.flush();

        for (ApplicationInfo appInfo : apps) {
            try {
                String packageName = appInfo.packageName;
                String sourceDir = appInfo.sourceDir != null ? appInfo.sourceDir : "";
                String dataDir = appInfo.dataDir != null ? appInfo.dataDir : "";

                String[] splitDirs = appInfo.splitSourceDirs;
                int splitCount = splitDirs != null ? splitDirs.length : 0;

                Resources appRes = !sourceDir.isEmpty() ? getAppResources(sourceDir, splitDirs) : null;

                String label = null;
                if (appRes != null && appInfo.labelRes != 0) {
                    try {
                        label = appRes.getString(appInfo.labelRes);
                    } catch (Exception e) {}
                }
                if (label == null || label.trim().isEmpty()) {
                    try {
                        label = pm.getApplicationLabel(appInfo).toString();
                    } catch (Exception e) {
                        label = packageName;
                    }
                }

                boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean isEnabled = appInfo.enabled;
                boolean hasSplits = splitCount > 0;

                int flags = 0;
                if (isSystem) flags |= 1;
                if (isEnabled) flags |= 2;
                if (hasSplits) flags |= 4;

                String versionName = "";
                long versionCode = 0;
                long firstInstallTime = 0;
                long lastUpdateTime = 0;
                try {
                    PackageInfo pkgInfo = pm.getPackageInfo(packageName, 0);
                    if (pkgInfo.versionName != null) versionName = pkgInfo.versionName;
                    versionCode = (Build.VERSION.SDK_INT >= 28)
                            ? pkgInfo.getLongVersionCode()
                            : pkgInfo.versionCode;
                    firstInstallTime = pkgInfo.firstInstallTime;
                    lastUpdateTime = pkgInfo.lastUpdateTime;
                } catch (Exception e) {
                    // benign for some pseudo-packages; leave defaults
                }

                int minSdk = (Build.VERSION.SDK_INT >= 24) ? appInfo.minSdkVersion : 0;
                int targetSdk = appInfo.targetSdkVersion;

                String installer = "";
                try {
                    installer = pm.getInstallerPackageName(packageName);
                    if (installer == null) installer = "";
                } catch (Exception e) {}

                byte[] iconBytes = new byte[0];
                if (includeIcons) {
                    Drawable iconDrawable = null;
                    if (appRes != null && appInfo.icon != 0) {
                        try {
                            iconDrawable = appRes.getDrawable(appInfo.icon);
                        } catch (Exception e) {}
                    }
                    if (iconDrawable == null) {
                        try {
                            iconDrawable = pm.getApplicationIcon(appInfo);
                        } catch (Exception e) {}
                    }
                    if (iconDrawable != null) {
                        try {
                            iconBytes = renderIconPng(iconDrawable);
                        } catch (Exception e) {}
                    }
                }

                out.writeByte(1);
                out.writeUTF(packageName);
                out.writeUTF(label);
                out.writeUTF(sourceDir);
                out.writeInt(splitCount);
                for (int i = 0; i < splitCount; i++) {
                    out.writeUTF(splitDirs[i] != null ? splitDirs[i] : "");
                }
                out.writeUTF(dataDir);
                out.writeInt(appInfo.uid);
                out.writeByte(flags);
                out.writeUTF(versionName);
                out.writeLong(versionCode);
                out.writeInt(minSdk);
                out.writeInt(targetSdk);
                out.writeLong(firstInstallTime);
                out.writeLong(lastUpdateTime);
                out.writeUTF(installer);
                out.writeInt(iconBytes.length);
                out.write(iconBytes);
                out.flush();

            } catch (Exception e) {
                // skip this app, continue with the rest
            }
        }

        out.writeByte(0);
        out.flush();
    }


    // Daemon mode

    /**
     * Per-PID CPU accounting sample, used to compute CPU% as a delta between
     * two /proc reads rather than requiring the client to poll twice.
     */
    static final class CpuSample {
        long procJiffies;    // utime+stime for this pid, at sample time
        long totalJiffies;   // system-wide total jiffies (/proc/stat), at sample time
        long startTimeTicks; // /proc/[pid]/stat field 22 - identifies THIS process instance,
                              // guards against comparing counters across a reused pid
        long sampledAtNanos;

        CpuSample(long procJiffies, long totalJiffies, long startTimeTicks, long sampledAtNanos) {
            this.procJiffies = procJiffies;
            this.totalJiffies = totalJiffies;
            this.startTimeTicks = startTimeTicks;
            this.sampledAtNanos = sampledAtNanos;
        }
    }

    static final int CPU_CORE_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors());

    static final class DaemonServer {
        final PackageManager pm;
        final Context systemContext;
        final Map<Integer, CpuSample> prevSamples = new HashMap<>();
        final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        volatile boolean shuttingDown = false;

        // System-wide CPU tracking (for overall CPU% delta across calls)
        long prevTotalJiffies = -1;
        long prevIdleJiffies = -1;
        double lastCpuUsagePercent = 0.0;

        DaemonServer() {
            systemContext = ActivityThread.systemMain().getSystemContext();
            pm = systemContext.getPackageManager();
        }

        void run() throws IOException {
            LocalServerSocket serverSocket;
            try {
                serverSocket = new LocalServerSocket(SOCKET_NAME);
            } catch (IOException e) {
                System.err.println("DioxAgent: failed to bind " + SOCKET_NAME + ": " + e);
                return;
            }

            // idle watchdog thread: if no client has connected in IDLE_TIMEOUT_MS, exit
            Thread watchdog = new Thread(() -> {
                try {
                    while (!shuttingDown) {
                        Thread.sleep(2000);
                        long idleFor = System.currentTimeMillis() - lastActivity.get();
                        if (idleFor >= IDLE_TIMEOUT_MS) {
                            shuttingDown = true;
                            try { serverSocket.close(); } catch (IOException ignored) {}
                            break;
                        }
                    }
                } catch (InterruptedException ignored) {}
            }, "diox-agent-idle-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            try {
                while (!shuttingDown) {
                    LocalSocket client;
                    try {
                        client = serverSocket.accept();
                    } catch (IOException e) {
                        // serverSocket.close() from watchdog lands here; treat as clean exit
                        break;
                    }
                    lastActivity.set(System.currentTimeMillis());
                    // handle synchronously: requests are short-lived request/response,
                    // one connection per command, so no concurrent-client complexity here.
                    try {
                        handleClient(client);
                    } catch (Exception e) {
                        System.err.println("DioxAgent: client handler error: " + e);
                    } finally {
                        try { client.close(); } catch (IOException ignored) {}
                    }
                }
            } finally {
                try { serverSocket.close(); } catch (IOException ignored) {}
            }
        }

        void handleClient(LocalSocket client) throws IOException {
            DataInputStream in = new DataInputStream(client.getInputStream());
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(client.getOutputStream()));

            int cmd = in.readUnsignedByte();
            try {
                switch (cmd) {
                    case CMD_LIST_PROCESSES:
                        handleListProcesses(out);
                        break;
                    case CMD_GET_ICON:
                        handleGetIcon(in, out);
                        break;
                    case CMD_FORCE_STOP:
                        handleForceStop(in, out);
                        break;
                    case CMD_KILL_PID:
                        handleKillPid(in, out);
                        break;
                    default:
                        writeError(out, "unknown command: " + cmd);
                }
            } catch (Exception e) {
                writeError(out, "handler exception: " + e);
            }
            out.flush();
        }

        static void writeError(DataOutputStream out, String message) throws IOException {
            out.writeByte(STATUS_ERROR);
            out.writeUTF(message != null ? message : "");
        }

        // CMD_LIST_PROCESSES 
        // Reads /proc/[pid]/{stat,status,cmdline}, /proc/stat, /proc/meminfo.
        // Running as shell (no root): on stock/AOSP-derived builds shell can read
        // /proc/[pid]/{stat,status,cmdline} for arbitrary app UIDs (same access
        // `ps`/`top`/`dumpsys` rely on as shell). Per-OEM `hidepid` hardening can
        // restrict this further - such PIDs are simply skipped, not fatal.
        void handleListProcesses(DataOutputStream out) throws IOException {
            long totalJiffiesNow = readTotalCpuJiffies();
            long nowNanos = System.nanoTime();

            File procDir = new File("/proc");
            File[] pidDirs = procDir.listFiles((dir, name) -> name.matches("\\d+"));
            if (pidDirs == null) pidDirs = new File[0];

            long memTotalKb = readMemTotalKb();
            long memAvailKb = readMemAvailableKb();

            // Compute overall CPU usage from /proc/stat idle-vs-total jiffies delta
            long idleJiffiesNow = readIdleCpuJiffies();
            if (prevTotalJiffies >= 0 && totalJiffiesNow > prevTotalJiffies) {
                long totalDelta = totalJiffiesNow - prevTotalJiffies;
                long idleDelta = idleJiffiesNow - prevIdleJiffies;
                lastCpuUsagePercent = Math.max(0.0,
                        Math.min(100.0, (1.0 - (double) idleDelta / totalDelta) * 100.0));
            }
            prevTotalJiffies = totalJiffiesNow;
            prevIdleJiffies = idleJiffiesNow;

            // collect successful entries first so the client gets an exact count,
            // not an upper bound it has to reconcile against per-entry skip flags
            List<ProcEntry> entries = new java.util.ArrayList<>(pidDirs.length);
            for (File pidDir : pidDirs) {
                int pid;
                try {
                    pid = Integer.parseInt(pidDir.getName());
                } catch (NumberFormatException e) {
                    continue;
                }
                try {
                    ProcEntry entry = readProcEntry(pid, totalJiffiesNow, nowNanos);
                    if (entry != null) entries.add(entry);
                } catch (Exception e) {
                    // skip this pid, continue with the rest
                }
            }

            out.writeByte(STATUS_OK);
            out.writeLong(memTotalKb);
            out.writeLong(memAvailKb);
            out.writeDouble(lastCpuUsagePercent);
            out.writeInt(CPU_CORE_COUNT);
            out.writeInt(entries.size()); // exact count of entries that follow, no skip-flag scanning needed
            for (ProcEntry entry : entries) {
                out.writeInt(entry.pid);
                out.writeInt(entry.uid);
                out.writeUTF(entry.processName != null ? entry.processName : "");
                out.writeUTF(entry.packageName != null ? entry.packageName : "");
                out.writeUTF(entry.appLabel != null ? entry.appLabel : "");
                out.writeLong(entry.rssKb);
                out.writeInt(entry.threadCount);
                out.writeDouble(entry.cpuPercent);
                out.writeBoolean(entry.isSystemApp);
            }

            // prevSamples is intentionally NOT cleared here it must persist
            // across calls so the next list_processes request has a delta to compute
            // against. Dead PIDs accumulate in the map over time; see prune below.
            prunestale(pidDirs);
        }

        // Drop cached samples for PIDs that no longer exist, so prevSamples doesn't
        // grow unboundedly across a long daemon lifetime.
        void prunestale(File[] livePidDirs) {
            if (prevSamples.size() < 256) return; // cheap, don't bother pruning small maps
            java.util.Set<Integer> live = new java.util.HashSet<>();
            for (File f : livePidDirs) {
                try { live.add(Integer.parseInt(f.getName())); } catch (NumberFormatException ignored) {}
            }
            prevSamples.keySet().retainAll(live);
        }

        static final class ProcEntry {
            int pid;
            int uid;
            String processName;
            String packageName;
            String appLabel;
            long rssKb;
            int threadCount;
            double cpuPercent;
            boolean isSystemApp;
        }

        ProcEntry readProcEntry(int pid, long totalJiffiesNow, long nowNanos) {
            File statFile = new File("/proc/" + pid + "/stat");
            File statusFile = new File("/proc/" + pid + "/status");
            File cmdlineFile = new File("/proc/" + pid + "/cmdline");

            if (!statFile.canRead()) return null;

            ProcEntry entry = new ProcEntry();
            entry.pid = pid;

            // cmdline -> process name (falls back to /proc/pid/stat's comm field)
            String cmdline = readCmdlineArg0(cmdlineFile);
            if (cmdline != null && !cmdline.isEmpty()) {
                entry.processName = cmdline;
            }

            long utime = 0, stime = 0, startTimeTicks = 0;
            int threads = 0;
            try {
                String stat = readFirstLine(statFile);
                if (stat == null) return null;
                // comm field can contain spaces/parens; parse from the last ')'
                int lastParen = stat.lastIndexOf(')');
                if (lastParen < 0) return null;
                String comm = stat.substring(stat.indexOf('(') + 1, lastParen);
                if (entry.processName == null || entry.processName.isEmpty()) {
                    entry.processName = comm;
                }
                String[] rest = stat.substring(lastParen + 2).trim().split("\\s+");
                // per proc(5): rest[11]=utime, rest[12]=stime, rest[19]=starttime (0-indexed from field 3 = state)
                utime = Long.parseLong(rest[11]);
                stime = Long.parseLong(rest[12]);
                threads = Integer.parseInt(rest[17]);
                startTimeTicks = Long.parseLong(rest[19]);
            } catch (Exception e) {
                return null;
            }
            entry.threadCount = threads;

            long procJiffiesNow = utime + stime;
            CpuSample prev = prevSamples.get(pid);
            // discard the previous sample if this pid was reused by a different process
            // since we last saw it (start time no longer matches)
            boolean samePidInstance = prev != null && prev.startTimeTicks == startTimeTicks;
            if (samePidInstance && totalJiffiesNow > prev.totalJiffies) {
                double procDelta = procJiffiesNow - prev.procJiffies;
                double totalDelta = totalJiffiesNow - prev.totalJiffies;
                // share of total system CPU capacity (0–100%),
                // consistent with the overall CPU usage metric
                entry.cpuPercent = totalDelta > 0
                        ? Math.max(0.0, (procDelta / totalDelta) * 100.0)
                        : 0.0;
            } else {
                entry.cpuPercent = 0.0; // first sample for this pid instance, no delta yet
            }
            prevSamples.put(pid, new CpuSample(procJiffiesNow, totalJiffiesNow, startTimeTicks, nowNanos));

            // status -> uid, RSS
            try (BufferedReader br = new BufferedReader(new FileReader(statusFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("Uid:")) {
                        String[] parts = line.split("\\s+");
                        entry.uid = Integer.parseInt(parts[1]);
                    } else if (line.startsWith("VmRSS:")) {
                        String[] parts = line.split("\\s+");
                        entry.rssKb = Long.parseLong(parts[1]);
                    }
                }
            } catch (Exception e) {
                // leave uid=0 / rss=0 if unreadable, entry still useful for pid/name
            }

            // map uid -> package name + label, if this looks like an app uid
            if (entry.uid >= 10000) {
                try {
                    String[] pkgs = pm.getPackagesForUid(entry.uid);
                    if (pkgs != null && pkgs.length > 0) {
                        entry.packageName = pkgs[0];
                        try {
                            ApplicationInfo ai = pm.getApplicationInfo(entry.packageName, 0);
                            entry.appLabel = pm.getApplicationLabel(ai).toString();
                            entry.isSystemApp = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        } catch (Exception e) {
                            entry.appLabel = entry.packageName;
                        }
                    }
                } catch (Exception e) {
                    // uid not resolvable to a package (isolated process, etc.) — fine, leave null
                }
            }

            return entry;
        }

        // /proc/[pid]/cmdline is NUL separated argv, not line-oriented text.
        // Reading via BufferedReader.readLine() happens to often "work" only because
        // there's usually no literal newline byte, but it's decoding through NUL bytes
        // with no defined contract for doing so. Read raw bytes and split on the first NUL.
        static String readCmdlineArg0(File f) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[256];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                byte[] data = out.toByteArray();
                int end = 0;
                while (end < data.length && data[end] != 0) {
                    end++;
                }
                return new String(data, 0, end, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }

        static String readFirstLine(File f) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                return br.readLine();
            } catch (Exception e) {
                return null;
            }
        }

        static long readTotalCpuJiffies() {
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
                String line = br.readLine(); // "cpu  user nice system idle iowait irq softirq ..."
                if (line == null) return 0;
                String[] parts = line.trim().split("\\s+");
                long total = 0;
                for (int i = 1; i < parts.length; i++) {
                    total += Long.parseLong(parts[i]);
                }
                return total;
            } catch (Exception e) {
                return 0;
            }
        }

        // idle = idle + iowait (fields 4 and 5 in /proc/stat's "cpu" line, 1-indexed)
        static long readIdleCpuJiffies() {
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
                String line = br.readLine();
                if (line == null) return 0;
                String[] parts = line.trim().split("\\s+");
                long idle = parts.length > 4 ? Long.parseLong(parts[4]) : 0;
                long iowait = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
                return idle + iowait;
            } catch (Exception e) {
                return 0;
            }
        }

        static long readMemTotalKb() {
            return readMeminfoField("MemTotal:");
        }

        static long readMemAvailableKb() {
            return readMeminfoField("MemAvailable:");
        }

        static long readMeminfoField(String key) {
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith(key)) {
                        String[] parts = line.trim().split("\\s+");
                        return Long.parseLong(parts[1]);
                    }
                }
            } catch (Exception e) {}
            return 0;
        }

        // CMD_GET_ICON 
        // payload: UTF packageName
        // Unaffected by root/shell distinction addAssetPath on a world-readable
        // APK path works fine as shell, same as the --icons path.
        void handleGetIcon(DataInputStream in, DataOutputStream out) throws IOException {
            String packageName = in.readUTF();
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                Resources appRes = getAppResources(appInfo.sourceDir, appInfo.splitSourceDirs);

                Drawable iconDrawable = null;
                if (appRes != null && appInfo.icon != 0) {
                    try {
                        iconDrawable = appRes.getDrawable(appInfo.icon);
                    } catch (Exception e) {}
                }
                if (iconDrawable == null) {
                    try {
                        iconDrawable = pm.getApplicationIcon(appInfo);
                    } catch (Exception e) {}
                }

                byte[] iconBytes = iconDrawable != null ? renderIconPng(iconDrawable) : new byte[0];
                out.writeByte(STATUS_OK);
                out.writeInt(iconBytes.length);
                out.write(iconBytes);
            } catch (Exception e) {
                writeError(out, "get_icon failed for " + packageName + ": " + e);
            }
        }

        //  CMD_FORCE_STOP
        // payload: UTF packageName
        // No root: `am force-stop` is a shell-invocable command wrapping the
        // signature|privileged Binder call; the shell UID is permitted to invoke
        // it via the `am` CLI wrapper even though it couldn't call the Binder
        // method directly. This is the reliable, portable path Om confirmed.
        void handleForceStop(DataInputStream in, DataOutputStream out) throws IOException {
            String packageName = in.readUTF();
            try {
                Process proc = Runtime.getRuntime().exec(new String[]{"am", "force-stop", packageName});
                int exitCode = proc.waitFor();
                if (exitCode == 0) {
                    out.writeByte(STATUS_OK);
                } else {
                    writeError(out, "am force-stop exited " + exitCode + " for " + packageName);
                }
            } catch (Exception e) {
                writeError(out, "force_stop failed for " + packageName + ": " + e);
            }
        }

        // CMD_KILL_PID 
        // as shell UID this will
        // almost always fail with EPERM against arbitrary app UID processes. Linux
        // signal-delivery permission checks require matching uid (or CAP_KILL), which
        // shell does not have over app processes. This is implemented for completeness
        // (eg killing a process the daemon itself spawned) but the client should treat
        // EPERM as an expected, common outcome not a bug and prefer force_stop for apps.
        void handleKillPid(DataInputStream in, DataOutputStream out) throws IOException {
            int pid = in.readInt();
            int signal = in.readInt();
            try {
                Os.kill(pid, signal);
                out.writeByte(STATUS_OK);
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EPERM) {
                    writeError(out, "EPERM: shell UID cannot signal pid " + pid + " (different uid, no CAP_KILL)");
                } else if (e.errno == OsConstants.ESRCH) {
                    writeError(out, "ESRCH: no such pid " + pid + " (already dead?)");
                } else {
                    writeError(out, "kill failed, errno=" + e.errno + ": " + e.getMessage());
                }
            } catch (Exception e) {
                writeError(out, "kill_pid failed: " + e);
            }
        }
    }
}