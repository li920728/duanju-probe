package com.djzj.probe;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;导入java.util.Date;
import java.util.IdentityHashMap;导入java.util.IdentityHashMap;
import java.util.Locale;导入java.util.Locale;importjava.util.Locale; 导入 java.util.Locale;
import java.util.Map;导入java.util.Map;importjava.util.Map; 导入 java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 探针 v3：多路并行采集播放进度。
 *
 * 上一版的结论（v2 实测）：
 * - exo_position 只在控制条可见时被 setText，控制条自动隐藏后停止更新 → 不可靠
 * - exo_progress 的类名是 s10，不是 ProgressBar，所以 hook ProgressBar.setProgress 拿不到
 *
 * 本版并行开四条通道，任意一条能持续产出递增数值，即为可用数据源：
 *   [TX ] TextView.setText（1 参 + 4 参重载）—— 仅控制条可见时有值
 *   [BAR] exo_progress 实际类及其父类的 setPosition/setDuration —— 按 id 动态定位，不写死类名
 *   [PLR] androidx.media3.exoplayer.ExoPlayerImpl#getCurrentPosition —— 直接取播放器状态
 *   [POL] 主线程轮询：每 2 秒反射读取 exo_progress 的 position/duration —— 完全不依赖 hook
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "DJZJ_PROBE";
    private static final String TARGET_PKG = "com.duanju.duanju_app.v2";
    private static final int MAX_DEPTH = 30;

    private static final String ID_POSITION = "exo_position";
    private static final String ID_DURATION = "exo_duration";
    private static final String ID_PROGRESS = "exo_progress";

    private static final long MIN_INTERVAL_MS = 200L;
    private static long lastTxAt = 0L;
    private static String lastTxLine = "";

    // 用于避免重复 hook 同一个类
    private static final Map<Class<?>, Boolean> hookedTimeBarClasses =
            new IdentityHashMap<Class<?>, Boolean>();

    private static boolean playerHooked = false;

    /**
     * 轮询目标列表。不能用单个 boolean 锁死：播放页退出重进或 controller 重建时，
     * exo_progress 会是新实例，旧实例已 detach，继续读它只会拿到旧值或异常。
     * 因此这里累积所有见过的 TimeBar 实例，逐个采样。
     */
    private static final java.util.List<View> pollTargets =
            new java.util.ArrayList<View>();
    private static boolean pollerStarted = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) {
        // MIUI 存在 packageName 与 processName 不一致的异常回调
        // （实测 packageName=com.miui.contentcatcher, processName=目标包名），
        // 因此按 processName 判断，而不是 packageName。
        String proc = lp.processName != null ? lp.processName : lp.packageName;
        if (proc == null || !proc.equals(TARGET_PKG)) {
            return;
        }

        log("========== MODULE LOADED ==========");
        log("packageName = " + lp.packageName);
        log("processName = " + lp.processName);
        log("isFirstApplication = " + lp.isFirstApplication);

        try {
            hookTextViewSetText(CharSequence.class);
        } catch (Throwable t) {
            log("[TX] hook setText(CharSequence) 失败: " + t);
        }
        try {
            hookTextViewSetText(CharSequence.class, TextView.BufferType.class,
                    boolean.class, int.class);
        } catch (Throwable t) {
            log("[TX] hook setText(4参) 失败: " + t);
        }
        log("[TX] 已 hook TextView.setText");

        try {
            hookProgressBarBaseline();
        } catch (Throwable t) {
            log("[BAR] hook ProgressBar 失败: " + t);
        }

        try {
            hookMedia3Player();
        } catch (Throwable t) {
            log("[PLR] hook ExoPlayerImpl 失败: " + t);
        }

        try {
            hookActivityForDump();
        } catch (Throwable t) {
            log("hook Activity 失败: " + t);
        }
    }

    // ==================== [TX] TextView.setText ====================

    private void hookTextViewSetText(Class<?>... paramTypes) {
        XposedHelpers.findAndHookMethod(TextView.class, "setText", paramTypes,
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
                            if (line.equals(lastTxLine)) return;
                            long now = System.currentTimeMillis();
                            if (now - lastTxAt < MIN_INTERVAL_MS) return;
                            lastTxAt = now;
                            lastTxLine = line;

                            log("[TX ] " + line + "  cls=" + v.getClass().getName());
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    /** 顺手保留：某些 App 会把进度画成 ProgressBar */
    private void hookProgressBarBaseline() {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    View v = (View) param.thisObject;
                    String id = idName(v);
                    if (id == null || !id.toLowerCase(Locale.ROOT).equals(ID_PROGRESS)) return;
                    ProgressBar pb = (ProgressBar) param.thisObject;
                    log("[BAR] ProgressBar id=" + id
                            + " progress=" + pb.getProgress()
                            + " max=" + pb.getMax()
                            + " cls=" + v.getClass().getName());
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(ProgressBar.class, "setProgress", int.class, h);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(ProgressBar.class, "setProgress", int.class,
                    boolean.class, h);
        } catch (Throwable ignored) {
        }
        log("[BAR] 已 hook ProgressBar.setProgress（兜底）");
    }

    // ==================== [BAR] DefaultTimeBar 动态 hook ====================

    /**
     * exo_progress 的实际类（s10，混淆后的 DefaultTimeBar）不是 ProgressBar，
     * 且类名不可预知。这里在 View attach 时按 id 定位到实例，
     * 再对它的实际类型及其所有父类 hook setPosition/setDuration。
     */
    private static void hookTimeBarFor(View v) {
        try {
            if (v == null) return;
            String id = idName(v);
            if (id == null || !id.toLowerCase(Locale.ROOT).equals(ID_PROGRESS)) return;

            Class<?> c = v.getClass();
            while (c != null && c != Object.class) {
                if (hookedTimeBarClasses.containsKey(c)) break;
                hookedTimeBarClasses.put(c, Boolean.TRUE);
                log("[BAR] 尝试 hook 类 " + c.getName());

                for (Method m : c.getDeclaredMethods()) {
                    String n = m.getName();
                    if (!n.equals("setPosition") && !n.equals("setDuration")
                            && !n.equals("setBufferedPosition")) {
                        continue;
                    }
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length != 1 || ps[0] != long.class) continue;
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                try {
                                    Object a = p.args != null && p.args.length > 0
                                            ? p.args[0] : null;
                                    log("[BAR] " + p.method.getName() + " arg=" + a);
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                        log("[BAR] 已 hook " + c.getName() + "#" + n + "(long)");
                    } catch (Throwable t) {
                        log("[BAR] hook " + c.getName() + "#" + n + " 失败: " + t);
                    }
                }
                c = c.getSuperclass();
            }

            startPollerFor(v);
        } catch (Throwable t) {
            log("[BAR] hookTimeBarFor 失败: " + t);
        }
    }

    // ==================== [POL] 反射轮询 ====================

    /**
     * 不依赖任何 hook：每 2 秒在主线程反射读取该 View 的 position/duration。
     * 如果 hook 全都没命中，这条通道仍然能拿到数据。
     */
    private static void startPollerFor(final View timeBar) {
        if (timeBar == null) return;
        if (!pollTargets.contains(timeBar)) {
            pollTargets.add(timeBar);
            log("[POL] 新增轮询目标 cls=" + timeBar.getClass().getName()
                    + " 共 " + pollTargets.size() + " 个");
        }
        if (pollerStarted) return;
        pollerStarted = true;

        Log.i(TAG, "[POL] 启动轮询采样器");
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    int n = pollTargets.size();
                    for (int i = 0; i < n; i++) {
                        View tb = pollTargets.get(i);
                        // 已 detach 的实例跳过，避免读到脏数据
                        if (tb == null || tb.getWindowToken() == null) continue;
                        Object pos = callGetter(tb, "getPosition");
                        Object dur = callGetter(tb, "getDuration");
                        log("[POL] idx=" + i
                                + " position=" + pos + " duration=" + dur
                                + " cls=" + tb.getClass().getName());
                    }
                    if (n == 0) {
                        log("[POL] 暂无轮询目标");
                    }
                } catch (Throwable t) {
                    log("[POL] 轮询异常: " + t);
                }
                h.postDelayed(this, 2000L);
            }
        }, 2000L);
    }

    private static Object callGetter(Object target, String name) {
        if (target == null) return "";
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                return "ERR:" + t.getClass().getSimpleName();
            }
            c = c.getSuperclass();
        }
        return "NO_METHOD";
    }

    // ==================== [PLR] media3 Player ====================

    private static void hookMedia3Player() {
        if (playerHooked) return;

        String[] classNames = new String[]{
                "androidx.media3.exoplayer.ExoPlayerImpl",
                "androidx.media3.exoplayer.SimpleExoPlayer",
        };

        for (String cn : classNames) {
            Class<?> c = null;
            try {
                c = XposedHelpers.findClass(cn, Hook.class.getClassLoader());
            } catch (Throwable t) {
                try {
                    c = XposedHelpers.findClass(cn, null);
                } catch (Throwable ignored) {
                }
            }
            if (c == null) {
                log("[PLR] 未找到类 " + cn);
                continue;
            }
            log("[PLR] 找到类 " + cn);

            hookPlayerMethod(c, "getCurrentPosition");
            hookPlayerMethod(c, "getDuration");
            hookPlayerMethod(c, "getContentPosition");
            playerHooked = true;
        }

        if (!playerHooked) {
            log("[PLR] 未 hook 到播放器，依赖 [BAR]/[POL]");
        }
    }

    private static void hookPlayerMethod(Class<?> c, String name) {
        try {
            XposedHelpers.findAndHookMethod(c, name, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        String n = p.method.getName();
                        Object r = p.getResult();
                        log("[PLR] " + n + " -> " + r);
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("[PLR] 已 hook " + c.getName() + "#" + name);
        } catch (Throwable t) {
            log("[PLR] hook " + name + " 失败: " + t);
        }
    }

    // ==================== Activity / dump ====================

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
                                scanWindow(a.getWindow());
                            }
                        }, 1500);
                    }
                });
    }

    /** dump 一次 exo_* 控件，同时给 exo_progress 挂上动态 hook 和轮询 */
    private static void scanWindow(Window w) {
        if (w == null) return;
        log("==== BEGIN VIEW SCAN ====");
        scanTree(w.getDecorView(), 0, new IdentityHashMap<View, Boolean>());
        log("==== END VIEW SCAN ====");
    }

    private static void scanTree(View v, int depth, Map<View, Boolean> seen) {
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

            if (low.equals(ID_PROGRESS)) {
                hookTimeBarFor(v);
            }
        }

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                scanTree(g.getChildAt(i), depth + 1, seen);
            }
        }
    }

    private私有 static String idName(View v) {
        int id = v.getId();整数 id= v.获取ID();
        if (id == View.NO_ID) return null;如果 (id == View.NO_ID) 返回 空;
        try {
            Context ctx = v.getContext();上下文 ctx= v.getContext();
            if (ctx != null) {如果 (ctx !=空) {
                String n = ctx.getResources().getResourceEntryName(id);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Throwable ignored) {
        }
        return "0x" + Integer.toHexString(id);返回 "0x"+ Integer.toHexString(id);
    }

    // ==================== 日志 ====================

    private static void log(String msg) {私有静态无返回值方法log(字符串msg){
        String line = ts() + " " + msg;字符串 行 = 时间戳() + " "+ 消息;

        try {
            Log.i(TAG, msg);日志。信息(标签, 消息);
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
        for (File dir : dirs) {对于 (文件 目录: 目录) {
            try {
                if (!dir.exists()) dir.mkdirs();如果 (!dir.存在())dir.创建目录();
                File f = new File(dir, "DJZJ_PROBE.log");文件 f = 新 文件(目录,"DJZJ_PROBE.log");文件 f = 新 文件(目录,"DJZJ_PROBE.log")；文件 f = 新 文件(目录,"DJZJ_PROBE.log");
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

    private static String ts() {私有 静态 字符串 ts() {私有 静态 字符串 ts() {私有 静态 字符串ts() {
        return返回 new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());返回 新 SimpleDateFormat("HH:mm:ss.SSS", Locale.US).格式化(新 日期());返回返回新SimpleDateFormat("HH:mm:ss.SSS", Locale.US).格式化新 日期;
    }
                                               }
