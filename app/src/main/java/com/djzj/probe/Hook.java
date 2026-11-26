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

import java.util.IdentityHashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 探针模块：不做任何界面修改，只打印信息。
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "DJZJ_PROBE";
    private static final int MAX_DEPTH = 30;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) {
        Log.i(TAG, "========== MODULE LOADED ==========");
        Log.i(TAG, "packageName = " + lp.packageName);
        Log.i(TAG, "processName = " + lp.processName);
        Log.i(TAG, "isFirstApplication = " + lp.isFirstApplication);

        if (lp.processName != null && lp.processName.contains(":")) {
            Log.i(TAG, "子进程，跳过 hook");
            return;
        }

        try {
            hookActivity();
            hookDialog();
        } catch (Throwable t) {
            Log.e(TAG, "hook 失败: " + Log.getStackTraceString(t));
        }
    }

    private void hookActivity() {
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate",
                Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity a = (Activity) param.thisObject;
                        Log.i(TAG, "[ACTIVITY onCreate] " + a.getClass().getName()
                                + "  intent=" + (a.getIntent() == null ? ""
                                : a.getIntent().getComponent()));
                    }
                });

        XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        final Activity a = (Activity) param.thisObject;
                        Log.i(TAG, "[ACTIVITY onResume] " + a.getClass().getName());

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
                            Log.i(TAG, "[ACTIVITY onConfigurationChanged] "
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
                            Log.i(TAG, "[DIALOG show] " + d.getClass().getName());
                            if (d.getWindow() != null) {
                                dumpWindow("DIALOG", d.getWindow());
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hookDialog 失败: " + Log.getStackTraceString(t));
        }
    }

    private static void dumpWindow(String tag, Window w) {
        if (w == null) {
            Log.i(TAG, "[" + tag + "] window == null");
            return;
        }
        View root = w.getDecorView();
        Log.i(TAG, "[" + tag + "] ==== BEGIN VIEW TREE ====");
        Map<View, Boolean> seen = new IdentityHashMap<>();
        dumpTree(root, 0, seen);
        Log.i(TAG, "[" + tag + "] ==== END VIEW TREE ====");
    }

    private static void dumpTree(View v, int depth, Map<View, Boolean> seen) {
        if (v == null || depth > MAX_DEPTH) return;
        if (seen.containsKey(v)) {
            Log.i(TAG, pad(depth) + "<cycle> " + v.getClass().getName());
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

        Log.i(TAG, sb.toString());

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                dumpTree(g.getChildAt(i), depth + 1, seen);
            }
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
