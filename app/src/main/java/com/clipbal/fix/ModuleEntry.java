package com.clipbal.fix;

import android.app.Application;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 修复 Android 17 BAL（后台启动 Activity）硬化导致智能剪贴板浮窗「開啟」按钮点击无效。
 *
 * 已证实根因（logcat）：
 *   E ActivityTaskManager: Background activity launch blocked! ...
 *     callingPackage: com.google.android.as
 *     originatingPendingIntent: PendingIntentRecord{ com.google.android.as startActivity }
 *     realCallingPackage: com.android.systemui
 *     balAllowedByPiCreator: BSP.NONE
 *   E ActivityTaskManager: Abort background activity starts from 10213
 *
 * 策略（双保险）：
 *  1) 主方案：Hook ClipboardOverlayView.setActionChip(RemoteAction, Runnable)，把
 *     AIS 创建的 RemoteAction 换成用 SystemUI Context 重新创建的 PendingIntent。
 *     这样点击时发送方与创建方都是 SystemUI（持 START_ACTIVITIES_FROM_BACKGROUND），
 *     BAL 检查结果为 BAL_ALLOW_PERMISSION，正常跳转。
 *  2) 兜底：Hook PendingIntent.send()，若仍有 AIS 创建的 VIEW PendingIntent 被发送，
 *     直接用 SystemUI Context startActivity。
 */
public final class ModuleEntry extends XposedModule {

    private static final String TAG = "ClipBalFix";

    private static final String SCOPE_PACKAGE = "com.android.systemui";
    private static final String AIS_PACKAGE = "com.google.android.as";
    private static final String EXT_SERVICES_PACKAGE = "android.ext.services";

    private static final String OVERLAY_VIEW_CLASS =
            "com.android.systemui.clipboardoverlay.ClipboardOverlayView";
    private static final String OVERLAY_CONTROLLER_CLASS =
            "com.android.systemui.clipboardoverlay.ClipboardOverlayController";

    private static final int MAX_URL_LENGTH = 4096;

    private final AtomicBoolean installed = new AtomicBoolean(false);
    private final List<XposedInterface.HookHandle> hookHandles =
            Collections.synchronizedList(new ArrayList<>());

    private volatile String processName = "";
    private volatile Context systemUiContext;
    private volatile Method pendingIntentGetIntent;

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "event=module_loaded process=" + processName
                + " api=" + getApiVersion()
                + " framework=" + getFrameworkName()
                + " version=" + getFrameworkVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!SCOPE_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        if (!SCOPE_PACKAGE.equals(processName)) {
            return;
        }
        installHooks(param.getDefaultClassLoader());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!SCOPE_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        if (!SCOPE_PACKAGE.equals(processName)) {
            return;
        }
        installHooks(param.getClassLoader());
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        log(Log.INFO, TAG, "event=hot_reloading");
        try {
            param.setSavedInstanceState(systemUiContext);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "event=hot_reloading_save_failed", t);
        }
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        try {
            Object saved = param.getSavedInstanceState();
            if (saved instanceof Context) {
                systemUiContext = (Context) saved;
            }
            for (XposedInterface.HookHandle handle : param.getOldHookHandles()) {
                try {
                    handle.unhook();
                } catch (Throwable ignored) {
                    // 旧 generation 的 handle 可能已失效。
                }
            }
            hookHandles.clear();
            installed.set(false);
            log(Log.INFO, TAG, "event=hot_reloaded");
            installHooks(getClass().getClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "event=hot_reload_failed", t);
        }
    }

    // ------------------------------------------------------------------- hooks

    private synchronized void installHooks(ClassLoader systemUiClassLoader) {
        if (!installed.compareAndSet(false, true)) {
            log(Log.INFO, TAG, "event=install_skipped reason=already_installed");
            return;
        }
        int overlayHooks = hookClipboardOverlay(systemUiClassLoader);
        int sendHooks = hookPendingIntentSend();
        hookApplicationCreate();
        log(Log.INFO, TAG, "event=hooks_installed overlay_hooks=" + overlayHooks
                + " send_hooks=" + sendHooks);
    }

    // ---- 主方案：替换 action chip 的 RemoteAction ---------------------------

    private int hookClipboardOverlay(ClassLoader classLoader) {
        int count = 0;
        ClassLoader loader = classLoader != null ? classLoader : getClass().getClassLoader();
        try {
            Class<?> overlayView = Class.forName(OVERLAY_VIEW_CLASS, false, loader);
            for (Method method : overlayView.getDeclaredMethods()) {
                if (!"setActionChip".equals(method.getName())) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                if (types.length != 2 || types[0] != RemoteAction.class) {
                    continue;
                }
                method.setAccessible(true);
                XposedInterface.HookHandle handle = hook(method)
                        .setId("overlay_set_action_chip")
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(chain -> replaceActionChip(chain));
                hookHandles.add(handle);
                count++;
                log(Log.INFO, TAG, "event=overlay_hook_ok method=" + method);
            }
            if (count == 0) {
                log(Log.WARN, TAG, "event=overlay_hook_not_found class=" + OVERLAY_VIEW_CLASS);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "event=overlay_hook_failed", t);
        }

        try {
            Class<?> controller = Class.forName(OVERLAY_CONTROLLER_CLASS, false, loader);
            for (Method method : controller.getDeclaredMethods()) {
                if (!"setActionChip".equals(method.getName())) {
                    continue;
                }
                log(Log.INFO, TAG, "event=controller_set_action_chip_present");
            }
        } catch (Throwable ignored) {
            // 控制器类名变化不影响主 Hook。
        }
        return count;
    }

    private Object replaceActionChip(XposedInterface.Chain chain) throws Throwable {
        try {
            Object[] args = chain.getArgs().toArray();
            if (args.length >= 1 && args[0] instanceof RemoteAction) {
                RemoteAction original = (RemoteAction) args[0];
                Context context = contextOf(chain.getThisObject());
                if (context != null) {
                    systemUiContext = context;
                    RemoteAction replacement = rebuildAction(original, context);
                    if (replacement != null) {
                        args[0] = replacement;
                        log(Log.INFO, TAG, "event=action_chip_replaced");
                        return chain.proceed(args);
                    }
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "event=replace_action_chip_failed", t);
        }
        return chain.proceed();
    }

    private RemoteAction rebuildAction(RemoteAction original, Context context) {
        try {
            PendingIntent sourcePendingIntent = original.getActionIntent();
            String creator = creatorPackageOf(sourcePendingIntent);
            if (creator != null && creator.equals(context.getPackageName())) {
                return null; // 已经是 SystemUI 自己的 PendingIntent，无需替换。
            }
            Intent intent = intentFromPendingIntent(sourcePendingIntent);
            if (intent == null) {
                intent = intentFromClipboard();
            }
            if (!isWebViewIntent(intent)) {
                return null;
            }
            Intent launch = new Intent(intent);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent replacementPendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            log(Log.INFO, TAG, "event=rebuild_action target=" + safeTarget(launch)
                    + " original_creator=" + creator);
            return new RemoteAction(
                    original.getIcon(),
                    original.getTitle(),
                    original.getContentDescription(),
                    replacementPendingIntent);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "event=rebuild_action_failed", t);
            return null;
        }
    }

    private static Context contextOf(Object view) {
        if (view instanceof View) {
            try {
                return ((View) view).getContext();
            } catch (Throwable ignored) {
                // fall through
            }
        }
        try {
            Field field = View.class.getDeclaredField("mContext");
            field.setAccessible(true);
            Object value = field.get(view);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- 兜底：拦截 PendingIntent.send() -----------------------------------

    private void hookApplicationCreate() {
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            XposedInterface.HookHandle handle = hook(onCreate)
                    .setId("systemui_application_oncreate")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Object self = chain.getThisObject();
                        if (self instanceof Context) {
                            systemUiContext = (Context) self;
                        }
                        return chain.proceed();
                    });
            hookHandles.add(handle);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "event=app_oncreate_hook_failed", t);
        }
    }

    private int hookPendingIntentSend() {
        int hooked = 0;
        for (Method method : PendingIntent.class.getDeclaredMethods()) {
            if (!"send".equals(method.getName())) {
                continue;
            }
            if (method.getReturnType() != void.class) {
                continue;
            }
            if (containsContextParameter(method)) {
                continue;
            }
            try {
                XposedInterface.HookHandle handle = hook(method)
                        .setId("pending_intent_send_" + method.getParameterCount())
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(this::handleSend);
                hookHandles.add(handle);
                hooked++;
            } catch (Throwable t) {
                log(Log.WARN, TAG, "event=send_hook_failed signature=" + method, t);
            }
        }
        return hooked;
    }

    private static boolean containsContextParameter(Method method) {
        for (Class<?> type : method.getParameterTypes()) {
            if (Context.class.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    private Object handleSend(XposedInterface.Chain chain) throws Throwable {
        boolean launched = false;
        try {
            launched = tryLaunch(chain);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "event=intercept_error fallback_to_original", t);
        }
        if (launched) {
            return null;
        }
        return chain.proceed();
    }

    private boolean tryLaunch(XposedInterface.Chain chain) {
        Object self = chain.getThisObject();
        if (!(self instanceof PendingIntent)) {
            return false;
        }
        PendingIntent pendingIntent = (PendingIntent) self;
        String creator = creatorPackageOf(pendingIntent);
        if (!AIS_PACKAGE.equals(creator) && !EXT_SERVICES_PACKAGE.equals(creator)) {
            return false;
        }
        Intent intent = intentFromArgs(chain);
        if (intent == null) {
            intent = intentFromPendingIntent(pendingIntent);
        }
        if (intent == null) {
            intent = intentFromClipboard();
        }
        if (!isWebViewIntent(intent)) {
            return false;
        }
        Context context = systemUiContext;
        if (context == null) {
            context = currentApplicationContext();
        }
        if (context == null) {
            log(Log.WARN, TAG, "event=no_context creator=" + creator);
            return false;
        }
        systemUiContext = context;
        Intent launch = new Intent(intent);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(launch);
            log(Log.INFO, TAG, "event=launch_ok target=" + safeTarget(launch)
                    + " creator=" + creator);
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "event=launch_failed target=" + safeTarget(launch), t);
            return false;
        }
    }

    // ----------------------------------------------------------------- helpers

    private static String creatorPackageOf(PendingIntent pendingIntent) {
        try {
            return pendingIntent.getCreatorPackage();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Intent intentFromArgs(XposedInterface.Chain chain) {
        try {
            List<Object> args = chain.getArgs();
            for (int i = 0; i < args.size(); i++) {
                Object arg = args.get(i);
                if (arg instanceof Intent) {
                    return (Intent) arg;
                }
            }
        } catch (Throwable ignored) {
            // 无参数重载走兜底。
        }
        return null;
    }

    private Intent intentFromPendingIntent(PendingIntent pendingIntent) {
        try {
            Method getIntent = pendingIntentGetIntent;
            if (getIntent == null) {
                getIntent = PendingIntent.class.getDeclaredMethod("getIntent");
                getIntent.setAccessible(true);
                pendingIntentGetIntent = getIntent;
            }
            Object value = getIntent.invoke(pendingIntent);
            return value instanceof Intent ? (Intent) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 兜底：浮窗出现时剪贴板里仍是刚复制的文本。SystemUI 有 READ_CLIPBOARD_IN_BACKGROUND。 */
    private Intent intentFromClipboard() {
        Context context = systemUiContext;
        if (context == null) {
            return null;
        }
        try {
            ClipboardManager clipboardManager =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) {
                return null;
            }
            ClipData clipData = clipboardManager.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() == 0) {
                return null;
            }
            CharSequence text = clipData.getItemAt(0).getText();
            if (text == null) {
                return null;
            }
            String raw = text.toString().trim();
            if (raw.isEmpty() || raw.length() > MAX_URL_LENGTH) {
                return null;
            }
            Uri uri = Uri.parse(raw);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            return new Intent(Intent.ACTION_VIEW, uri);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isWebViewIntent(Intent intent) {
        return intent != null
                && Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.getData() != null;
    }

    private static Context currentApplicationContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object value = current.invoke(null);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 日志脱敏：只记录 scheme + host，不落完整路径和查询参数。 */
    private static String safeTarget(Intent intent) {
        Uri data = intent.getData();
        if (data == null) {
            return "null";
        }
        String scheme = data.getScheme();
        String host = data.getHost();
        return (scheme == null ? "?" : scheme) + "://" + (host == null ? "?" : host);
    }
}
