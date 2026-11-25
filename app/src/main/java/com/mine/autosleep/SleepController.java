package com.mine.autosleep;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

final class SleepController {
    private static final String TAG = "SleepController";

    // Wi‑Fi / Bluetooth via svc
    private static final String CMD_WIFI_OFF = "svc wifi disable";
    private static final String CMD_WIFI_ON  = "svc wifi enable";
    private static final String CMD_BT_OFF   = "svc bluetooth disable";
    private static final String CMD_BT_ON    = "svc bluetooth enable";

    // Doze force/unforce
    private static final String CMD_FORCE_IDLE = "dumpsys deviceidle force-idle";
    private static final String CMD_UNFORCE_IDLE = "dumpsys deviceidle unforce";

    // Airplane mode commands
    private static final String CMD_AP_ON_1  = "settings put global airplane_mode_on 1";
    private static final String CMD_AP_ON_2  = "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true";
    private static final String CMD_AP_OFF_1 = "settings put global airplane_mode_on 0";
    private static final String CMD_AP_OFF_2 = "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false";

    // Settings keys we snapshot/restore when supported
    private static final String CMD_GET_SCAN_ALWAYS =
            "/system/bin/settings get global wifi_scan_always_enabled 2>/dev/null";
    private static final String CMD_GET_WAKEUP_ENABLED =
            "/system/bin/settings get global wifi_wakeup_enabled 2>/dev/null";

    private static final String CMD_PUT_SCAN_ALWAYS_0 =
            "/system/bin/settings put global wifi_scan_always_enabled 0";
    private static final String CMD_PUT_WAKEUP_ENABLED_0 =
            "/system/bin/settings put global wifi_wakeup_enabled 0";

    private SleepController() {}

    static boolean isSleepActive(Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return sp.getBoolean(Constants.PREF_SLEEP_ACTIVE, false);
    }

    static void enterSleep(Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        if (sp.getBoolean(Constants.PREF_SLEEP_ACTIVE, false)) {
            Log.d(TAG, "enterSleep: already active");
            return;
        }

        boolean enableAirplane = sp.getBoolean(Constants.PREF_ENABLE_AIRPLANE_MODE, true);
        boolean disableWifi    = sp.getBoolean(Constants.PREF_DISABLE_WIFI, true);
        boolean disableBt      = sp.getBoolean(Constants.PREF_DISABLE_BLUETOOTH, true);
        boolean forceDoze      = sp.getBoolean(Constants.PREF_FORCE_DOZE, true);
        boolean disableSensors = sp.getBoolean(Constants.PREF_DISABLE_SENSORS, true);

        // Snapshot current states only for enabled toggles (read via su)
        Snapshot snap = readSnapshotViaSu(enableAirplane, disableWifi, disableBt);

        SharedPreferences.Editor editor = sp.edit()
                .putBoolean(Constants.SNAP_APPLIED_AIRPLANE, enableAirplane)
                .putBoolean(Constants.SNAP_APPLIED_WIFI, disableWifi)
                .putBoolean(Constants.SNAP_APPLIED_BT, disableBt)
                .putBoolean(Constants.SNAP_APPLIED_DOZE, forceDoze)
                .putBoolean(Constants.SNAP_APPLIED_SENSORS, disableSensors)
                .putBoolean(Constants.SNAP_VALID, true);

        if (enableAirplane) {
            editor.putInt(Constants.SNAP_AIRPLANE, snap.airplane);
        } else {
            editor.remove(Constants.SNAP_AIRPLANE);
        }

        if (disableWifi) {
            editor.putInt(Constants.SNAP_WIFI, snap.wifi)
                    .putInt(Constants.SNAP_WIFI_SCAN_ALWAYS, snap.wifiScanAlways)         // -1 if unsupported
                    .putInt(Constants.SNAP_WIFI_WAKEUP_ENABLED, snap.wifiWakeupEnabled)   // -1 if unsupported
                    .putString(Constants.SNAP_WIFI_UP_IFACES, snap.wifiStaUpIfacesCsv);   // STA-only (may be empty)
        } else {
            editor.remove(Constants.SNAP_WIFI)
                    .remove(Constants.SNAP_WIFI_SCAN_ALWAYS)
                    .remove(Constants.SNAP_WIFI_WAKEUP_ENABLED)
                    .remove(Constants.SNAP_WIFI_UP_IFACES);
        }

        if (disableBt) {
            editor.putInt(Constants.SNAP_BT, snap.bt);
        } else {
            editor.remove(Constants.SNAP_BT);
        }

        editor.apply();

        // Apply enabled sleep actions in requested order:
        // Airplane ON, Wi-Fi OFF + STA down + scan/wakeup OFF, BT OFF, Sensors OFF, Force idle
        List<String> cmds = new ArrayList<>();

        if (enableAirplane) {
            cmds.add(CMD_AP_ON_1);
            cmds.add(CMD_AP_ON_2);
        }

        if (disableWifi) {
            // Framework Wi‑Fi OFF (AOSP svc->cmd mapping keeps this consistent)
            cmds.add(CMD_WIFI_OFF);

            // Bring DOWN only STA interfaces that were UP at entry
            addIfaceCommands(cmds, snap.wifiStaUpIfacesCsv, false /*down*/);

            // Disable scan-always / wakeup only if supported and currently enabled in snapshot
            if (snap.wifiScanAlways == 1) cmds.add(CMD_PUT_SCAN_ALWAYS_0);
            if (snap.wifiWakeupEnabled == 1) cmds.add(CMD_PUT_WAKEUP_ENABLED_0);
        }

        if (disableBt) {
            cmds.add(CMD_BT_OFF);
        }

        if (disableSensors) {
            String sensorCmd = getSensorPrivacyCommand(true);
            if (sensorCmd != null) {
                cmds.add(sensorCmd);
            }
        }

        if (forceDoze) {
            cmds.add(CMD_FORCE_IDLE);
        }

        if (!cmds.isEmpty()) {
            runSu(cmds.toArray(new String[0]));
        }

        sp.edit().putBoolean(Constants.PREF_SLEEP_ACTIVE, true).apply();
        Log.d(TAG, "enterSleep: done");
    }

    static void exitSleep(Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);

        if (!sp.getBoolean(Constants.PREF_SLEEP_ACTIVE, false)) {
            if (sp.getBoolean(Constants.PREF_FORCE_DOZE, true)) {
                runSu(CMD_UNFORCE_IDLE);
            }
            Log.d(TAG, "exitSleep: not active");
            return;
        }

        boolean hasSnap = sp.getBoolean(Constants.SNAP_VALID, false);

        boolean restoreAirplane = wasToggleEnabled(sp, hasSnap, Constants.SNAP_APPLIED_AIRPLANE, Constants.PREF_ENABLE_AIRPLANE_MODE);
        boolean restoreWifi     = wasToggleEnabled(sp, hasSnap, Constants.SNAP_APPLIED_WIFI, Constants.PREF_DISABLE_WIFI);
        boolean restoreBt       = wasToggleEnabled(sp, hasSnap, Constants.SNAP_APPLIED_BT, Constants.PREF_DISABLE_BLUETOOTH);
        boolean restoreDoze     = wasToggleEnabled(sp, hasSnap, Constants.SNAP_APPLIED_DOZE, Constants.PREF_FORCE_DOZE);
        boolean restoreSensors  = wasToggleEnabled(sp, hasSnap, Constants.SNAP_APPLIED_SENSORS, Constants.PREF_DISABLE_SENSORS);

        List<String> cmds = new ArrayList<>();

        if (restoreDoze) {
            cmds.add(CMD_UNFORCE_IDLE);
        }

        if (restoreSensors) {
            String sensorCmd = getSensorPrivacyCommand(false);
            if (sensorCmd != null) {
                cmds.add(sensorCmd);
            }
        }

        if (restoreWifi) {
            int wifi           = hasSnap ? sp.getInt(Constants.SNAP_WIFI, 0) : 0;
            int scanAlways     = hasSnap ? sp.getInt(Constants.SNAP_WIFI_SCAN_ALWAYS, -1) : -1;
            int wakeupEnabled  = hasSnap ? sp.getInt(Constants.SNAP_WIFI_WAKEUP_ENABLED, -1) : -1;
            String upStaIfaces = hasSnap ? sp.getString(Constants.SNAP_WIFI_UP_IFACES, "") : "";

            // Restore Wi‑Fi settings BEFORE enabling Wi‑Fi and before bringing interfaces up
            if (scanAlways == 0 || scanAlways == 1) {
                cmds.add("/system/bin/settings put global wifi_scan_always_enabled " + scanAlways);
            }
            if (wakeupEnabled == 0 || wakeupEnabled == 1) {
                cmds.add("/system/bin/settings put global wifi_wakeup_enabled " + wakeupEnabled);
            }

            // Bring UP only the STA interfaces that were UP at entry
            addIfaceCommands(cmds, upStaIfaces, true /*up*/);

            // Restore framework Wi‑Fi after restoring settings & interfaces
            if (wifi == 1) cmds.add(CMD_WIFI_ON);
            else cmds.add(CMD_WIFI_OFF);
        }

        if (restoreBt) {
            int bt = hasSnap ? sp.getInt(Constants.SNAP_BT, 0) : 0;
            if (bt == 1) cmds.add(CMD_BT_ON);
            else cmds.add(CMD_BT_OFF);
        }

        if (restoreAirplane) {
            int airplane = hasSnap ? sp.getInt(Constants.SNAP_AIRPLANE, 0) : 0;
            if (airplane == 1) {
                cmds.add(CMD_AP_ON_1);
                cmds.add(CMD_AP_ON_2);
            } else {
                cmds.add(CMD_AP_OFF_1);
                cmds.add(CMD_AP_OFF_2);
            }
        }

        if (!cmds.isEmpty()) {
            runSu(cmds.toArray(new String[0]));
        }

        sp.edit()
                .putBoolean(Constants.PREF_SLEEP_ACTIVE, false)
                .putBoolean(Constants.SNAP_VALID, false)
                .remove(Constants.SNAP_APPLIED_AIRPLANE)
                .remove(Constants.SNAP_APPLIED_WIFI)
                .remove(Constants.SNAP_APPLIED_BT)
                .remove(Constants.SNAP_APPLIED_DOZE)
                .remove(Constants.SNAP_APPLIED_SENSORS)
                .apply();

        Log.d(TAG, "exitSleep: restored snapshot and cleared state");
    }

    private static boolean wasToggleEnabled(
            SharedPreferences sp, boolean hasSnap, String snapAppliedKey, String prefKey) {
        boolean prefValue = sp.getBoolean(prefKey, true);
        return hasSnap ? sp.getBoolean(snapAppliedKey, prefValue) : prefValue;
    }

    /**
     * Returns the version-appropriate Binder command for sensor privacy, or null below Android 10 (API 29).
     */
    static String getSensorPrivacyCommand(boolean disable) {
        int code;
        if (Build.VERSION.SDK_INT >= 33) {
            code = 9;
        } else if (Build.VERSION.SDK_INT >= 31) {
            code = 8;
        } else if (Build.VERSION.SDK_INT >= 29) {
            code = 4;
        } else {
            return null;
        }
        return "service call sensor_privacy " + code + " i32 " + (disable ? 1 : 0);
    }

    private static void addIfaceCommands(List<String> cmds, String ifacesCsv, boolean up) {
        if (ifacesCsv == null) return;
        String trimmed = ifacesCsv.trim();
        if (trimmed.isEmpty()) return;

        String[] parts = trimmed.split(",");
        for (String p : parts) {
            if (p == null) continue;
            String iface = p.trim();
            if (iface.isEmpty()) continue;
            cmds.add("ip link set " + iface + (up ? " up" : " down"));
        }
    }

    private static void runSu(String... commands) {
        if (commands == null || commands.length == 0) return;
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            for (String cmd : commands) {
                os.writeBytes(cmd + "\n");
            }
            os.writeBytes("exit\n");
            os.flush();
            p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "runSu failed: " + e.getMessage(), e);
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static Snapshot readSnapshotViaSu(boolean readAirplane, boolean readWifi, boolean readBt) {
        Snapshot s = new Snapshot();
        s.airplane = 0;
        s.wifi = 0;
        s.bt = 0;

        s.wifiScanAlways = -1;
        s.wifiWakeupEnabled = -1;
        s.wifiStaUpIfacesCsv = "";

        if (!readAirplane && !readWifi && !readBt) {
            return s;
        }

        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());

            if (readAirplane) {
                os.writeBytes("echo AP=$(/system/bin/settings get global airplane_mode_on 2>/dev/null)\n");
            }
            if (readWifi) {
                os.writeBytes("echo WIFI=$(/system/bin/settings get global wifi_on 2>/dev/null)\n");
                os.writeBytes("echo SCAN=$(" + CMD_GET_SCAN_ALWAYS + ")\n");
                os.writeBytes("echo WAKEUP=$(" + CMD_GET_WAKEUP_ENABLED + ")\n");

                // Build CSV of STA interfaces that are UP
                os.writeBytes(
                        "STA_LIST=$(iw dev 2>/dev/null | " +
                                "awk '$1==\"Interface\"{i=$2} $1==\"type\" && $2==\"managed\"{print i}');\n" +
                        "CSV=\"\";\n" +
                        "for I in $STA_LIST; do " +
                        "  S=$(cat /sys/class/net/$I/operstate 2>/dev/null); " +
                        "  [ \"$S\" = \"up\" ] && CSV=\"$CSV$I,\"; " +
                        "done;\n" +
                        "echo IFACES=$CSV\n"
                );
            }
            if (readBt) {
                os.writeBytes("echo BT=$(/system/bin/settings get global bluetooth_on 2>/dev/null)\n");
            }

            os.writeBytes("exit\n");
            os.flush();

            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("AP=")) s.airplane = parse01(line.substring(3));
                else if (line.startsWith("WIFI=")) s.wifi = parse01(line.substring(5));
                else if (line.startsWith("BT=")) s.bt = parse01(line.substring(3));

                else if (line.startsWith("SCAN=")) s.wifiScanAlways = parseNullable01(line.substring(5));
                else if (line.startsWith("WAKEUP=")) s.wifiWakeupEnabled = parseNullable01(line.substring(7));

                else if (line.startsWith("IFACES=")) {
                    String csv = line.substring(7).trim();
                    if (csv.endsWith(",")) csv = csv.substring(0, csv.length() - 1);
                    s.wifiStaUpIfacesCsv = csv;
                }
            }

            p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "readSnapshotViaSu failed: " + e.getMessage(), e);
        } finally {
            if (p != null) p.destroy();
        }
        return s;
    }

    private static int parse01(String v) {
        try {
            v = v.trim();
            return "1".equals(v) ? 1 : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int parseNullable01(String v) {
        if (v == null) return -1;
        v = v.trim();
        if (v.isEmpty()) return -1;
        if ("null".equalsIgnoreCase(v)) return -1;
        return "1".equals(v) ? 1 : 0;
    }

    private static final class Snapshot {
        int airplane;
        int wifi;
        int bt;

        int wifiScanAlways;      // -1 unsupported, else 0/1
        int wifiWakeupEnabled;   // -1 unsupported, else 0/1

        // STA-only interfaces that were UP at entry, e.g. "wlan0,wlan1"
        String wifiStaUpIfacesCsv;
    }
}
