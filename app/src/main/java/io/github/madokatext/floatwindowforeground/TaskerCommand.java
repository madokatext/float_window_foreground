package io.github.madokatext.floatwindowforeground;

import android.content.ComponentName;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Root app_process entry point for stowing the focused task using ColorOS window controls.
 */
public final class TaskerCommand {
    private static final int DEFAULT_DISPLAY = 0;
    private static final int STANDARD_ACTIVITY = 1;
    private static final int FULLSCREEN_WINDOW = 1;
    private static final int FLEXIBLE_WINDOW_SCENARIO = 1;
    private static final int FLOAT_HANDLE_STATE = 4;
    private static final int MINIMIZE_EVENT = 2002;
    private static final long STATE_TIMEOUT_MS = 3_000L;
    private static final long POLL_INTERVAL_MS = 80L;

    private TaskerCommand() {
    }

    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0) {
                throw new IllegalStateException("Enable Use Root in Tasker's Run Shell action.");
            }
            if (Build.VERSION.SDK_INT != 35) {
                throw new IllegalStateException("This command targets ColorOS on Android 15.");
            }
            stowFocusedTask(new WindowServices());
        } catch (InvocationTargetException error) {
            fail(error.getCause() != null ? error.getCause() : error);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    private static void fail(Throwable error) {
        System.err.println("Float Window Foreground: " + error);
        System.exit(1);
    }

    private static void stowFocusedTask(WindowServices windows)
            throws ReflectiveOperationException {
        Object task = windows.focusedTask();
        if (task == null || !windows.isAppTask(task)) {
            throw new IllegalStateException("No supported foreground app task on the main display.");
        }
        int taskId = windows.taskId.getInt(task);
        if (windows.isStowed(task)) {
            System.out.println("Task " + taskId + " is already stowed.");
            return;
        }

        if (!windows.isFlexible(task)) {
            // The native minimize event expects an existing FlexibleWindow task.
            if ((int) windows.getWindowingMode.invoke(task) != FULLSCREEN_WINDOW
                    || !(boolean) windows.enterFlexible.invoke(windows.oplus, null, taskId,
                            false, true)) {
                throw new IllegalStateException("ColorOS refused to put task " + taskId
                        + " into a floating window.");
            }
        }

        windows.waitForState(taskId, false);
        windows.notifyFlexibleTaskEvent.invoke(windows.oplus, taskId, MINIMIZE_EVENT, null);
        windows.waitForState(taskId, true);
        System.out.println("Task " + taskId + " was stowed to its ColorOS edge icon.");
    }

    private static final class WindowServices {
        final Object oplus;
        final Method enterFlexible;
        final Method notifyFlexibleTaskEvent;
        final Method getWindowingMode;
        final Field taskId;

        private final Object activityTaskManager;
        private final Method getTasks;
        private final Method getActivityType;
        private final Field focused;
        private final Field visible;
        private final Field topActivity;
        private final Field vendorExtras;

        WindowServices() throws ReflectiveOperationException {
            Class<?> atm = Class.forName("android.app.ActivityTaskManager");
            Class<?> atmInterface = Class.forName("android.app.IActivityTaskManager");
            Class<?> oplusManager = Class.forName("android.app.OplusActivityTaskManager");
            Class<?> taskInfo = Class.forName("android.app.TaskInfo");

            activityTaskManager = atm.getMethod("getService").invoke(null);
            getTasks = atmInterface.getMethod("getTasks",
                    int.class, boolean.class, boolean.class, int.class);
            oplus = oplusManager.getMethod("getInstance").invoke(null);
            enterFlexible = oplusManager.getMethod("toggleFlexibleWindow",
                    IBinder.class, int.class, boolean.class, boolean.class);
            notifyFlexibleTaskEvent = oplusManager.getMethod("notifyFlexibleTaskEvent",
                    int.class, int.class, Bundle.class);
            getWindowingMode = taskInfo.getMethod("getWindowingMode");
            getActivityType = taskInfo.getMethod("getActivityType");
            taskId = taskInfo.getField("taskId");
            focused = taskInfo.getField("isFocused");
            visible = taskInfo.getField("isVisible");
            topActivity = taskInfo.getField("topActivity");
            vendorExtras = taskInfo.getField("mOplusExtraBundle");
        }

        private List<?> getTasks(int limit) throws ReflectiveOperationException {
            return (List<?>) getTasks.invoke(activityTaskManager,
                    limit, false, false, DEFAULT_DISPLAY);
        }

        Object focusedTask() throws ReflectiveOperationException {
            List<?> tasks = getTasks(1);
            if (tasks.isEmpty()) {
                return null;
            }
            Object task = tasks.get(0);
            return focused.getBoolean(task) && visible.getBoolean(task) ? task : null;
        }

        boolean isAppTask(Object task) throws ReflectiveOperationException {
            ComponentName component = (ComponentName) topActivity.get(task);
            if (component == null || (int) getActivityType.invoke(task) != STANDARD_ACTIVITY) {
                return false;
            }
            return switch (component.getPackageName()) {
                case "com.android.launcher", "com.android.systemui",
                        "net.dinglisch.android.taskerm", "com.joaomgcd.taskerm" -> false;
                default -> true;
            };
        }

        boolean isFlexible(Object task) throws ReflectiveOperationException {
            Bundle extras = (Bundle) vendorExtras.get(task);
            return extras != null
                    && extras.getInt("androidx.activity.LaunchScenario") == FLEXIBLE_WINDOW_SCENARIO
                    && extras.getBoolean("androidx.activity.HasCaption")
                    && !extras.getBoolean("androidx.activity.LaunchEmbedded");
        }

        boolean isStowed(Object task) throws ReflectiveOperationException {
            Bundle extras = (Bundle) vendorExtras.get(task);
            return extras != null
                    && extras.getInt("key_flexible_task_state") == FLOAT_HANDLE_STATE;
        }

        void waitForState(int id, boolean stowed) throws ReflectiveOperationException {
            long deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS;
            do {
                for (Object task : getTasks(64)) {
                    if (taskId.getInt(task) != id) {
                        continue;
                    }
                    boolean ready = stowed
                            ? isStowed(task) && !focused.getBoolean(task)
                            : readyToStow(task);
                    if (ready) {
                        return;
                    }
                    break;
                }
                SystemClock.sleep(POLL_INTERVAL_MS);
            } while (SystemClock.elapsedRealtime() < deadline);

            String expected = stowed ? "edge icon" : "floating window";
            throw new IllegalStateException("ColorOS did not confirm the " + expected
                    + " state for task " + id + ".");
        }

        private boolean readyToStow(Object task) throws ReflectiveOperationException {
            if (!isFlexible(task) || isStowed(task) || !visible.getBoolean(task)) {
                return false;
            }
            Bundle extras = (Bundle) vendorExtras.get(task);
            // ColorOS emits this value after creating the caption view for this task.
            return extras.containsKey("androidx.activity.flexibleTaskCaptionViewMaxScale");
        }
    }
}
