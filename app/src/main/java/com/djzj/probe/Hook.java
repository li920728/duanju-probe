package com.djzj.probe;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 探针模块 v2：日志同时写 logcat 和文件。
 *
 * 为什么要写文件：手机端抓 logcat 门槛高，写文件后可直接用文件管理器打开看。
 *
 * 文件位置（两处都写，哪处写成功用哪处）：
 * 1. /sdcard/Android/data/目标包名/files/DJZJ_PROBE.log   ← 首选，App 一定有权限
 * 2. /sdcard/DJZJ_PROBE.log                              ← 备用，部分系统无权限
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "DJZJ_PROBE";
    private static final int MAX_DEPTH = 30;
    private static final String FILE_NAME = "DJZJ_PROBE.log";

    /** 目标 App 的 Context，用于拿私有目录；拿到前为 null */
    private static Context sAppContext;
    /** 已确认可写的日志文件，避免每次都重新探测 */
    private static File sLogFile;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) {
        log("========== MODULE LOADED ==========");
        log("packageName = " + lp.packageName);
        log("processName = " + lp.processName);
        log("isFirstApplication = " + lp.isFirstApplication);

        if (lp.processName != null && lp.processName.contains(":")) {
            log("子进程，跳过 hook");
            return;
        }
        // 尽早 hook Application.attach，拿到 Context 后才能写私有目录
        hookApplicationAttach(lp.classLoader);


        try {
            hookActivity();
            hookDialog();
        } catch (Throwable t) {
            log("hook 失败: " + Log.getStackTraceString(t));
        }
    }

    /** 拿到 App Context —— 日志文件路径依赖它 */
    private void hookApplicationAttach(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Application", cl, "attach",
                    Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sAppContext = (Context) param.args[0];
                            log("AppContext 已获取");
                        }
                    });
        } catch (Throwable t) {
            log("hookApplicationAttach 失败: " + t);
        }
    }

    private void hookActivity() {
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate",
                Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity a = (Activity) param.thisObject;
                        if (sAppContext == null) sAppContext = a.getApplicationContext();
                        log("[ACTIVITY onCreate] " + a.getClass().getName()
                                + "  intent=" + (a.getIntent() == null ? "null"
                                : a.getIntent().getComponent()));
                    }
                });

        XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        final Activity a = (Activity) param.thisObject;
                        if (sAppContext == null) sAppContext = a.getApplicationContext();
                        log("[ACTIVITY onResume] " + a.getClass().getName());

                        a.getWindow().getDecorView().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                dumpWindow("RESUME", a.getWindow());
                            }
                        }, 1200);
                    }
                });

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onConfigurationChanged",
                    android.content.res.Configuration.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity a = (Activity) param.thisObject;
                            log("[ACTIVITY onConfigurationChanged] "
                                    + a.getClass().getName());
                        }
                    });
        } catch (Throwable ignore) {
        }
    }

    private void hookDialog() {
        try {
            XposedHelpers.findAndHookMethod(Dialog.class, "show",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Dialog d = (Dialog) param.thisObject;
                            log("[DIALOG show] " + d.getClass().getName());
                            if (d.getWindow() != null) {
                                dumpWindow("DIALOG", d.getWindow());
                            }
                        }
                    });
        } catch (Throwable t) {
            log("hookDialog 失败: " + Log.getStackTraceString(t));
        }
    }

    private static void dumpWindow(String tag, Window w) {
        if (w == null) {
            log("[" + tag + "] window == null");
            return;
        }
        View root = w.getDecorView();
        log("[" + tag + "] ==== BEGIN VIEW TREE ====");
        Map<View, Boolean> seen = new IdentityHashMap<>();
        dumpTree(root, 0, seen);
        log("[" + tag + "] ==== END VIEW TREE ====");
    }

    private static void dumpTree(View v, int depth, Map<View, Boolean> seen) {
        if (v == null || depth > MAX_DEPTH) return;
        if (seen.containsKey(v)) {
            log(pad(depth) + "<cycle> " + v.getClass().getName());
            return;
        }
        seen.put(v, Boolean.TRUE);

        StringBuilder sb = new StringBuilder();
        sb.append(pad(depth))
          .append('[').append(v.getClass().getName()).append(']')
          .append(" id=").append(idName(v))
          .append(" size=").append(v.getWidth()).append('x').append(v.getHeight())
          .append(" vis=").append(visName(v.getVisibility()));

        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) {
                String s = t.toString().replace('\n', ' ');
                if (s.length() > 40) s = s.substring(0, 40) + "...";
                sb.append(" text=\"").append(s).append('"');
            }
        }

        String cn = v.getClass().getName();
        if (cn.contains("SurfaceView") || cn.contains("TextureView")
                || cn.contains("VideoView") || cn.contains("Player")
                || cn.contains("Render") || cn.contains("GLSurface")) {
            sb.append("  <<<< PLAYER_CANDIDATE");
        }

        log(sb.toString());

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                dumpTree(g.getChildAt(i), depth + 1, seen);
            }
        }
    }

    // ================= 日志输出：logcat + 文件 =================

    private static void log(String msg) {
        Log.i(TAG, msg);
        writeToFile(msg);
    }

    private static synchronized void writeToFile(String msg) {
        File f = pickLogFile();
        if (f == null) return;
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
            String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            w.write(ts + "  " + msg + "\n");
        } catch (Throwable ignored) {
        } finally {
            if (w != null) try { w.close(); } catch (Throwable ignored) { }
        }
    }

    /** 选出第一个能写的路径，选中后缓存 */
    private static synchronized File pickLogFile() {
        if (sLogFile != null) return sLogFile;

        // 首选：App 私有外部目录，无需任何存储权限
        File base = null;
        try {
            if (sAppContext != null) {
                base = sAppContext.getExternalFilesDir(null);
            }
        } catch (Throwable ignored) {
        }
        if (base != null) {
            File f = new File(base, FILE_NAME);
            if (canWrite(f)) {
                sLogFile = f;
                safeHint(f.getAbsolutePath());
                return sLogFile;
            }
        }

        // 备用：公共 sdcard 根目录
        File fallback = new File("/sdcard/" + FILE_NAME);
        if (canWrite(fallback)) {
            sLogFile = fallback;
            safeHint(fallback.getAbsolutePath());
            return sLogFile;
        }
        return null;
    }

    private static boolean canWrite(File f) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(f, true);
            out.write(new byte[0]);
            out.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 首次确定路径时，往 logcat 打个提示，方便对照 */
    private static void safeHint(String path) {
        try {
            Log.i(TAG, "日志文件路径 = " + path);
        } catch (Throwable ignored) {
        }
    }

    private static String pad(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        return sb.toString();
    }

    private static String visName(int vis) {
        switch (vis) {
            case View.VISIBLE: return "V";
            case View.INVISIBLE: return "I";
            case View.GONE: return "G";
            default: return "?";
        }
    }

    private static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return "NO_ID";
        try {
            Context ctx = v.getContext();
            if (ctx != null) {
                String n = ctx.getResources().getResourceEntryName(id);
                if (n != null) return n;
            }
        } catch (Throwable ignored) {
        }
        return "0x" + Integer.toHexString(id);
    }
}
