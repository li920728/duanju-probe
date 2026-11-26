package com.djzj.probe;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "DJZJ_PROBE";
    private static final String TARGET_PKG = "com.duanju.duanju_app.v2";
    private static final int MAX_DEPTH = 30;

    private static final String ID_POSITION = "exo_position";
    private static final String ID_DURATION = "exo_duration";
    private static final String ID_PROGRESS = "exo_progress";

    private static final long MIN_INTERVAL_MS = 250L;
    private static long lastProgressLogAt = 0L;
    private static String lastProgressLine = "";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) {
        String proc = lp.processName != null ? lp.processName : lp.packageName;
        if (proc == null || !proc.equals(TARGET_PKG)) {
            return;
        }

        log("========== MODULE LOADED ==========");
        log("packageName = " + lp.packageName);
        log("processName = " + lp.processName);
        log("isFirstApplication = " + lp.isFirstApplication);

        try {
            hookTextViewSetText();
        } catch (Throwable t) {
            log("hook setText 失败: " + Log.getStackTraceString(t));
        }

        try {
            hookProgressBar();
        } catch (Throwable t) {
            log("hook ProgressBar 失败: " + Log.getStackTraceString(t));
        }

        try {
            hookActivityForDump();
        } catch (Throwable t) {
            log("hook Activity 失败: " + Log.getStackTraceString(t));
        }
    }

    private void hookTextViewSetText() {
        XposedHelpers.findAndHookMethod(TextView.class, "setText",
                CharSequence.class, TextView.BufferType.class,
                boolean.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            View v = (View) param.thisObject;
                            String id = idName(v);
                            if (id == null || id.isEmpty()) return;

                            String low = id.toLowerCase(Locale.ROOT);
                            if (!low.equals(ID_POSITION) && !low.equals(ID_DURATION)) return;

                            Object arg0 = param.args != null && param.args.length > 0
                                    ? param.args[0] : null;
                            String text = arg0 == null ? "" : arg0.toString();

                            String line = "[" + id + "] " + text;
                            if (line.equals(lastProgressLine)) return;
                            long now = System.currentTimeMillis();
                            if (now - lastProgressLogAt < MIN_INTERVAL_MS) return;
                            lastProgressLogAt = now;
                            lastProgressLine = line;

                            log("TX " + line + "  cls=" + v.getClass().getName());
                        } catch (Throwable ignored) {
                        }
                    }
                });
        log("已 hook TextView.setText");
    }

    private void hookProgressBar() {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    View v = (View) param.thisObject;
                    String id = idName(v);
                    if (id == null || !id.toLowerCase(Locale.ROOT).equals(ID_PROGRESS)) return;

                    ProgressBar pb = (ProgressBar) param.thisObject;
                    log("PB id=" + id
                            + " progress=" + pb.getProgress()
                            + " max=" + pb.getMax()
                            + " cls=" + v.getClass().getName());
                } catch (Throwable ignored) {
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(ProgressBar.class, "setProgress",
                    int.class, h);
        } catch (Throwable t) {
            log("hook setProgress(int) 失败: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(ProgressBar.class, "setProgress",
                    int.class, boolean.class, h);
        } catch (Throwable t) {
            log("hook setProgress(int,boolean) 失败: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(ProgressBar.class, "setMax",
                    int.class, h);
        } catch (Throwable t) {
            log("hook setMax 失败: " + t);
        }
        log("已 hook ProgressBar.setProgress/setMax");
    }

    private void hookActivityForDump() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        final Activity a = (Activity) param.thisObject;
                        log("[ACTIVITY onResume] " + a.getClass().getName());
                        a.getWindow().getDecorView().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                dumpWindow(a.getWindow());
                            }
                        }, 1500);
                    }
                });
    }

    private static void dumpWindow(Window w) {
        if (w == null) return;
        log("==== BEGIN VIEW TREE ====");
        dumpTree(w.getDecorView(), 0, new IdentityHashMap<View, Boolean>());
        log("==== END VIEW TREE ====");
    }

    private static void dumpTree(View v, int depth, Map<View, Boolean> seen) {
        if (v == null || depth > MAX_DEPTH) return;
        if (seen.containsKey(v)) return;
        seen.put(v, Boolean.TRUE);

        String id = idName(v);
        String low = id == null ? "" : id.toLowerCase(Locale.ROOT);
        if (low.startsWith("exo_")) {
            StringBuilder sb = new StringBuilder();
            sb.append("[VIEW] id=").append(id)
              .append(" cls=").append(v.getClass().getName())
              .append(" size=").append(v.getWidth()).append('x').append(v.getHeight())
              .append(" vis=").append(v.getVisibility());
            if (v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                sb.append(" text=\"").append(t == null ? "" : t).append('"');
            }
            if (v instanceof ProgressBar) {
                sb.append(" progress=").append(((ProgressBar) v).getProgress())
                  .append(" max=").append(((ProgressBar) v).getMax());
            }
            log(sb.toString());
        }

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpTree(g.getChildAt(i), depth + 1, seen);
            }
        }
    }

    private static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return null;
        try {
            Context ctx = v.getContext();
            if (ctx != null) {
                String n = ctx.getResources().getResourceEntryName(id);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Throwable ignored) {
        }
        return "0x" + Integer.toHexString(id);
    }

    private static void log(String msg) {
        String line = ts() + " " + msg;

        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }

        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
        }

        File[] dirs = new File[]{
                new File("/sdcard/Android/data/" + TARGET_PKG + "/files"),
                new File("/storage/emulated/0/Android/data/" + TARGET_PKG + "/files"),
        };
        for (File dir : dirs) {
            try {
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, "DJZJ_PROBE.log");
                FileWriter fw = new FileWriter(f, true);
                fw.write(line);
                fw.write('\n');
                fw.flush();
                fw.close();
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static String ts() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
