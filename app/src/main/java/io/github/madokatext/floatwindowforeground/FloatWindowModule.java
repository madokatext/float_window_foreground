package io.github.madokatext.floatwindowforeground;

import android.app.ActivityOptions;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.SurfaceControl;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.annotations.AfterInvocation;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;
import io.github.libxposed.api.errors.XposedFrameworkError;

public final class FloatWindowModule extends XposedModule {
    private static final String TAG = "FloatWindowForeground";
    private static final int DISPLAY_DEFAULT = 0;
    private static final int TRANSIT_TO_FLOAT_HANDLE = 16;
    private static final int TASK_FRAGMENT_VISIBLE = 0;
    private static volatile WindowHooks active;

    public FloatWindowModule(XposedInterface base, ModuleLoadedParam param) {
        super(base, param);
    }

    @Override
    public void onSystemServerLoaded(SystemServerLoadedParam param) {
        if (Build.VERSION.SDK_INT != 35 || active != null) {
            return;
        }

        List<MethodUnhooker<Method>> installed = new ArrayList<>();
        try {
            WindowHooks hooks = new WindowHooks(param.getClassLoader());
            installed.add(hook(hooks.minimizeAnimationStart, MinimizeAnimationStart.class));
            installed.add(hook(hooks.pauseInRecentsAnimation, PauseInRecentsAnimation.class));
            installed.add(hook(hooks.exitFlexibleTask, ExitFlexibleTask.class));
            installed.add(hook(hooks.addToStopping, AddToStopping.class));
            installed.add(hook(hooks.getVisibility, GetVisibility.class));
            installed.add(hook(hooks.isFocusableAndVisible, IsFocusableAndVisible.class));
            installed.add(hook(hooks.shouldIgnoreInput, ShouldIgnoreInput.class));
            installed.add(hook(hooks.prepareSurfaces, PrepareSurfaces.class));
            installed.add(hook(hooks.openAnimationStart, OpenAnimationStart.class));
            installed.add(hook(hooks.removeFloatHandle, FloatHandlesChanged.class));
            installed.add(hook(hooks.removeAllFloatHandles, FloatHandlesChanged.class));
            installed.add(hook(hooks.updateFloatHandleList, FloatHandlesChanged.class));
            for (Method caller : hooks.inputAndFocusCallers) {
                deoptimize(caller);
            }
            active = hooks;
            Log.i(TAG, "ColorOS float handle hooks installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError
                 | XposedFrameworkError error) {
            for (int i = installed.size() - 1; i >= 0; i--) {
                try {
                    installed.get(i).unhook();
                } catch (RuntimeException | LinkageError | XposedFrameworkError unhookError) {
                    error.addSuppressed(unhookError);
                }
            }
            Log.w(TAG, "Float handle hooks are unavailable on this ROM", error);
        }
    }

    private static final class WindowHooks {
        private static final String WM = "com.android.server.wm.";

        private final AtomicBoolean reportedError = new AtomicBoolean();
        private final ThreadLocal<Object> animatingClose = new ThreadLocal<>();
        private final Map<Integer, WeakReference<Object>> stowedTasks = new ConcurrentHashMap<>();

        final Method minimizeAnimationStart;
        final Method pauseInRecentsAnimation;
        final Method exitFlexibleTask;
        final Method addToStopping;
        final Method getVisibility;
        final Method isFocusableAndVisible;
        final Method shouldIgnoreInput;
        final Method prepareSurfaces;
        final Method openAnimationStart;
        final Method removeFloatHandle;
        final Method removeAllFloatHandles;
        final Method updateFloatHandleList;
        final List<Method> inputAndFocusCallers;

        private final Method getTask;
        private final Method getActivityTask;
        private final Method getDisplay;
        private final Method getDisplayId;
        private final Method isRootTask;
        private final Method isLeafFragment;
        private final Method isAttached;
        private final Method isForceHidden;
        private final Method showToCurrentUser;
        private final Method isActivityTypeStandard;
        private final Method isFlexibleWindow;
        private final Method getTopActivity;
        private final Method isActivityAttached;
        private final Method isDisplaySleeping;
        private final Method isKeyguardLocked;
        private final Method getFloatHandleController;
        private final Method hasFloatHandle;
        private final Method getSurface;
        private final Method getTransaction;
        private final Method ensureActivitiesVisible;
        private final Field strategyTask;
        private final Field taskFragment;
        private final Field taskId;
        private final Field lastSurfaceShowing;
        private final Field taskAtms;
        private final Field atmsHandler;
        private final Field atmsRoot;
        private final Field atmsLock;

        WindowHooks(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> task = load(loader, "Task");
            Class<?> fragment = load(loader, "TaskFragment");
            Class<?> activity = load(loader, "ActivityRecord");
            Class<?> display = load(loader, "DisplayContent");
            Class<?> atms = load(loader, "ActivityTaskManagerService");
            Class<?> controller = load(loader, "FlexibleTaskController");
            Class<?> floatHandles = load(loader, "FloatHandleController");
            Class<?> floatHandleInfo = load(loader, "floathandle.FloatHandleInfo");
            Class<?> minimizeStrategy = load(loader, "ExitFlexibleTaskToFloatStrategy");
            Class<?> fragmentExtension = load(loader, "TaskFragmentExtImpl");

            minimizeAnimationStart = method(minimizeStrategy, "onMinimizeAnimationStartInner",
                    controller, floatHandleInfo, atms, boolean.class);
            pauseInRecentsAnimation = method(fragmentExtension, "pauseInRecentsAnim");
            exitFlexibleTask = method(controller, "exitFlexibleTask",
                    task, boolean.class, int.class, int.class);
            addToStopping = method(activity, "addToStopping",
                    boolean.class, boolean.class, String.class);
            getVisibility = method(fragment, "getVisibility", activity);
            isFocusableAndVisible = method(fragment, "isFocusableAndVisible");
            shouldIgnoreInput = method(task, "shouldIgnoreInput");
            prepareSurfaces = method(task, "prepareSurfaces");
            openAnimationStart = method(floatHandles, "onFloatOpenAnimationStartInner",
                    task, boolean.class, android.graphics.Rect.class, floatHandleInfo);
            removeFloatHandle = method(floatHandles, "removeFloatHandleInner",
                    int.class, boolean.class, int.class);
            removeAllFloatHandles = method(floatHandles, "removeAllFloatHandle");
            updateFloatHandleList = method(floatHandles, "updateInfoMapForAdd", floatHandleInfo);

            // These framework callers can contain inlined copies of the focus and input checks.
            Class<?> taskDisplayArea = load(loader, "TaskDisplayArea");
            Class<?> windowState = load(loader, "WindowState");
            inputAndFocusCallers = List.of(
                    method(taskDisplayArea, "getFocusedRootTask"),
                    method(taskDisplayArea, "getNextFocusableRootTask", task, boolean.class),
                    method(windowState, "canReceiveKeys", boolean.class),
                    method(windowState, "canReceiveTouchInput"));

            Class<?> container = load(loader, "WindowContainer");
            getTask = method(fragment, "getTask");
            getActivityTask = method(activity, "getTask");
            getDisplay = method(container, "getDisplayContent");
            getDisplayId = method(fragment, "getDisplayId");
            isRootTask = method(task, "isRootTask");
            isLeafFragment = method(fragment, "isLeafTaskFragment");
            isAttached = method(fragment, "isAttached");
            isForceHidden = method(fragment, "isForceHidden");
            showToCurrentUser = method(task, "showToCurrentUser");
            isActivityTypeStandard = method(load(loader, "ConfigurationContainer"),
                    "isActivityTypeStandard");
            isFlexibleWindow = method(load(loader, "FlexibleWindowUtils"),
                    "isFlexibleTaskAndHasCaption", task);
            getTopActivity = method(fragment, "getTopNonFinishingActivity");
            isActivityAttached = method(activity, "attachedToProcess");
            isDisplaySleeping = method(display, "isSleeping");
            isKeyguardLocked = method(display, "isKeyguardLocked");
            getFloatHandleController = method(floatHandles, "getInstance");
            hasFloatHandle = method(floatHandles, "isInFloatingList", int.class);
            getSurface = method(container, "getSurfaceControl");
            getTransaction = method(container, "getSyncTransaction");
            ensureActivitiesVisible = method(load(loader, "RootWindowContainer"),
                    "ensureActivitiesVisible");
            strategyTask = field(load(loader, "AbsFlexibleTaskExitStrategy"), "mTask");
            taskFragment = field(fragmentExtension, "mTaskFragment");
            taskId = field(task, "mTaskId");
            lastSurfaceShowing = field(task, "mLastSurfaceShowing");
            taskAtms = field(fragment, "mAtmService");
            atmsHandler = field(atms, "mH");
            atmsRoot = field(atms, "mRootWindowContainer");
            atmsLock = field(atms, "mGlobalLock");
        }

        private static Class<?> load(ClassLoader loader, String simpleName)
                throws ClassNotFoundException {
            return Class.forName(WM + simpleName, false, loader);
        }

        private static Method method(Class<?> owner, String name, Class<?>... parameters)
                throws NoSuchMethodException {
            Method method = owner.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        }

        private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        private void report(Exception error) {
            if (reportedError.compareAndSet(false, true)) {
                Log.w(TAG, "Failed to apply a float handle hook", error);
            }
        }

        private boolean supportsTask(Object task) throws ReflectiveOperationException {
            return task != null
                    && (int) getDisplayId.invoke(task) == DISPLAY_DEFAULT
                    && (boolean) isRootTask.invoke(task)
                    && (boolean) isLeafFragment.invoke(task)
                    && (boolean) isActivityTypeStandard.invoke(task)
                    && (boolean) isFlexibleWindow.invoke(null, task);
        }

        private boolean hasHandle(int id) throws ReflectiveOperationException {
            Object controller = getFloatHandleController.invoke(null);
            return controller != null && (boolean) hasFloatHandle.invoke(controller, id);
        }

        private boolean isStowed(Object task) throws ReflectiveOperationException {
            if (task == null || stowedTasks.isEmpty()) {
                return false;
            }
            int id = taskId.getInt(task);
            WeakReference<Object> entry = stowedTasks.get(id);
            return entry != null && entry.get() == task && hasHandle(id);
        }

        private boolean keepVisible(Object task) throws ReflectiveOperationException {
            // Running behind an opaque task can be ignored while the native handle exists.
            // Keep the real force-hide, screen and user checks.
            if (!isStowed(task) || !(boolean) isAttached.invoke(task)
                    || (boolean) isForceHidden.invoke(task)
                    || !(boolean) showToCurrentUser.invoke(task)) {
                return false;
            }
            Object display = getDisplay.invoke(task);
            if (display == null || (int) getDisplayId.invoke(task) != DISPLAY_DEFAULT
                    || (boolean) isDisplaySleeping.invoke(display)
                    || (boolean) isKeyguardLocked.invoke(display)) {
                return false;
            }
            Object top = getTopActivity.invoke(task);
            return top != null && (boolean) isActivityAttached.invoke(top);
        }

        private void setClosingTask(Object task) {
            if (task == null) {
                animatingClose.remove();
            } else {
                animatingClose.set(task);
            }
        }

        private void hideSurface(Object task) throws ReflectiveOperationException {
            SurfaceControl surface = (SurfaceControl) getSurface.invoke(task);
            if (surface != null && surface.isValid()) {
                SurfaceControl.Transaction transaction =
                        (SurfaceControl.Transaction) getTransaction.invoke(task);
                transaction.setVisibility(surface, false);
                // Force stock surface placement to reconsider showing it on restore.
                lastSurfaceShowing.setBoolean(task, false);
            }
        }

        private void removeStaleHandles() throws ReflectiveOperationException {
            for (Map.Entry<Integer, WeakReference<Object>> entry : stowedTasks.entrySet()) {
                Object task = entry.getValue().get();
                if ((task == null || !hasHandle(entry.getKey()))
                        && stowedTasks.remove(entry.getKey(), entry.getValue()) && task != null) {
                    refreshVisibility(task);
                }
            }
        }

        private void refreshVisibility(Object task) throws ReflectiveOperationException {
            Object atms = taskAtms.get(task);
            Handler handler = (Handler) atmsHandler.get(atms);
            handler.post(() -> {
                try {
                    synchronized (atmsLock.get(atms)) {
                        Object root = atmsRoot.get(atms);
                        if (root != null) {
                            ensureActivitiesVisible.invoke(root);
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    report(error);
                }
            });
        }
    }

    @XposedHooker
    public static final class MinimizeAnimationStart implements Hooker {
        @BeforeInvocation
        public static Object before(BeforeHookCallback callback) {
            WindowHooks hooks = active;
            if (hooks == null) {
                return null;
            }
            Object previous = hooks.animatingClose.get();
            hooks.setClosingTask(null);
            try {
                Object task = hooks.strategyTask.get(callback.getThisObject());
                if (hooks.supportsTask(task) && hooks.hasHandle(hooks.taskId.getInt(task))) {
                    hooks.setClosingTask(task);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                hooks.report(error);
            }
            return previous;
        }

        @AfterInvocation
        public static void after(AfterHookCallback callback, Object previous) {
            WindowHooks hooks = active;
            if (hooks != null) {
                hooks.setClosingTask(previous);
            }
        }
    }

    @XposedHooker
    public static final class PauseInRecentsAnimation implements Hooker {
        @BeforeInvocation
        public static void before(BeforeHookCallback callback) {
            WindowHooks hooks = active;
            if (hooks == null) {
                return;
            }
            Object closing = hooks.animatingClose.get();
            if (closing == null) {
                return;
            }
            try {
                Object fragment = hooks.taskFragment.get(callback.getThisObject());
                if (hooks.getTask.invoke(fragment) == closing) {
                    callback.returnAndSkip(null);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                hooks.report(error);
            }
        }
    }

    @XposedHooker
    public static final class ExitFlexibleTask implements Hooker {
        @BeforeInvocation
        public static WeakReference<Object> before(BeforeHookCallback callback) {
            WindowHooks hooks = active;
            if (hooks == null || (int) callback.getArgs()[2] != TRANSIT_TO_FLOAT_HANDLE) {
                return null;
            }
            Object task = callback.getArgs()[0];
            try {
                if (hooks.supportsTask(task) && hooks.hasHandle(hooks.taskId.getInt(task))) {
                    // Register before the stock move-to-back code recomputes visibility.
                    WeakReference<Object> entry = new WeakReference<>(task);
                    hooks.stowedTasks.put(hooks.taskId.getInt(task), entry);
                    return entry;
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                hooks.report(error);
            }
            return null;
        }

        @AfterInvocation
        public static void after(AfterHookCallback callback, WeakReference<Object> entry) {
            WindowHooks hooks = active;
            if (hooks == null || entry == null || callback.getThrowable() == null) {
                return;
            }
            Object task = entry.get();
            if (task == null) {
                return;
            }
            try {
                hooks.stowedTasks.remove(hooks.taskId.getInt(task), entry);
                hooks.refreshVisibility(task);
            } catch (ReflectiveOperationException | RuntimeException error) {
                hooks.report(error);
            }
        }
    }

    @XposedHooker
    public static final class AddToStopping implements Hooker {
        @BeforeInvocation
        public static void before(BeforeHookCallback callback) {
            WindowHooks hooks = active;
            if (hooks == null || !"startToFloatAnimation".equals(callback.getArgs()[2])) {
                return;
            }
            try {
                Object activity = callback.getThisObject();
                if (hooks.keepVisible(hooks.getActivityTask.invoke(activity))) {
                    callback.returnAndSkip(null);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                hooks.report(error);
            }
        }
    }

    @XposedHooker
    public static final class GetVisibility implements Hooker {
        @AfterInvocation
        public static void after(AfterHookCallback callback) {
            WindowHooks hooks = active;
            if (hooks == null || callback.getThrowable() != null
                    || Integer.valueOf(TASK_FRAGMENT_VISIBLE).equals(callback.getResult())) {
                return;
            }
            try {
                Object fragment = callback.getThisObject();
                if (hooks.getTask.invoke(fragment) == fragment && hooks.keepVisible(fragment)) {
                    callback.setResult(TASK_FRAGMENT_VISIBLE);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                hooks.report(error);
            }
        }
    }

    @XposedHooker
    public static final class IsFocusableAndVisible implements Hooker {
        @AfterInvocation
        public static void after(AfterHookCallback callback) {
            WindowHooks hooks = active;
            if (hooks == null || callback.getThrowable() != null
                    || !Boolean.TRUE.equals(callback.getResult())) {
                return;
            }
            try {
                Object fragment = callback.getThisObject();
                if (hooks.getTask.invoke(fragment) == fragment && hooks.isStowed(fragment)) {
                    callback.setResult(false);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                hooks.report(error);
            }
        }
    }

    @XposedHooker
    public static final class ShouldIgnoreInput implements Hooker {
        @AfterInvocation
        public static void after(AfterHookCallback callback) {
            WindowHooks hooks = active;
            if (hooks == null || callback.getThrowable() != null
                    || !Boolean.FALSE.equals(callback.getResult())) {
                return;
            }
            try {
                if (hooks.isStowed(callback.getThisObject())) {
                    callback.setResult(true);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                hooks.report(error);
            }
        }
    }

    @XposedHooker
    public static final class PrepareSurfaces implements Hooker {
        @AfterInvocation
        public static void after(AfterHookCallback callback) {
            WindowHooks hooks = active;
            if (hooks == null || callback.getThrowable() != null) {
                return;
            }
            try {
                Object task = callback.getThisObject();
                if (hooks.isStowed(task)) {
                    hooks.hideSurface(task);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                hooks.report(error);
            }
        }
    }

    @XposedHooker
    public static final class OpenAnimationStart implements Hooker {
        @BeforeInvocation
        public static void before(BeforeHookCallback callback) {
            WindowHooks hooks = active;
            if (hooks == null || callback.getArgs()[0] == null) {
                return;
            }
            try {
                Object task = callback.getArgs()[0];
                int id = hooks.taskId.getInt(task);
                WeakReference<Object> entry = hooks.stowedTasks.get(id);
                if (entry != null && entry.get() == task) {
                    hooks.stowedTasks.remove(id, entry);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                hooks.report(error);
            }
        }
    }

    @XposedHooker
    public static final class FloatHandlesChanged implements Hooker {
        @AfterInvocation
        public static void after(AfterHookCallback callback) {
            WindowHooks hooks = active;
            if (hooks == null || hooks.stowedTasks.isEmpty()) {
                return;
            }
            try {
                hooks.removeStaleHandles();
            } catch (ReflectiveOperationException | RuntimeException error) {
                hooks.report(error);
            }
        }
    }
}
