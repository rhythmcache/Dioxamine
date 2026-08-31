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
import android.os.Build;
import android.os.Looper;
import android.util.DisplayMetrics;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

public class PkgDump {

    static final int ICON_SIZE = 48;
    static final String SELF_PATH = "/data/local/tmp/pkg-dump.jar";

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

    public static void main(String[] args) throws Exception {
        selfDestruct();

        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }

        Context systemContext = ActivityThread.systemMain().getSystemContext();
        PackageManager pm = systemContext.getPackageManager();

        boolean includeIcons = args.length > 0 && args[0].equals("--icons");

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
}