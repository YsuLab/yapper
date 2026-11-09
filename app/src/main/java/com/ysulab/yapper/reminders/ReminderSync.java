package com.ysulab.yapper.reminders;

import com.ysulab.yapper.sort.ActionLog;
import java.io.IOException;
import java.time.OffsetDateTime;

/**
 * Where each reminder of a sorted capture rings, and keeping that in the action log so Undo can
 * remove it. Pure Java over two small interfaces, so the rules are unit-tested.
 *
 * <p>Rules (see docs/decisions.md, M3):
 * <ul>
 *   <li>To Do not set up → exact local alarm only ("local").
 *   <li>Due within the safety window (default 60 min) → local alarm rings, and the To Do task is
 *       created <i>without</i> its own reminder, so Samsung Reminder does not ring a second time ("both").
 *   <li>Later → To Do task with a reminder; Samsung Reminder rings ("todo"). If the push is still
 *       pending when the time gets close (offline), the local alarm takes over.
 *   <li>Already past → To Do task without a reminder, for the record ("past").
 * </ul>
 */
public final class ReminderSync {

    /** Local exact alarms (AlarmManager on the phone). */
    public interface Alarms {
        void schedule(int id, long atMs, String title, String captureId);

        void cancel(int id);
    }

    /** The owner's To Do list. */
    public interface Todo {
        String create(String taskJson) throws Exception;

        void delete(String listId, String taskId) throws Exception;

        String listId();
    }

    public static final long DEFAULT_WINDOW_MS = 60 * 60_000L;
    static final long PAST_GRACE_MS = 60_000L;

    private final String captureId;
    private final Alarms alarms;
    private final long windowMs;

    public ReminderSync(String captureId, Alarms alarms, long windowMs) {
        this.captureId = captureId;
        this.alarms = alarms;
        this.windowMs = windowMs;
    }

    /** Stable request code per reminder, so re-scheduling replaces instead of duplicating. */
    public static int alarmId(String captureId, int index) {
        int h = (captureId + "/" + index).hashCode() & 0x3fffffff;
        return h == 0 ? 1 : h;
    }

    static long atMs(ActionLog.Entry e) {
        return OffsetDateTime.parse(e.remindAt).toInstant().toEpochMilli();
    }

    /**
     * Runs right after the vault writes, offline. Decides each new reminder's route and sets the
     * local alarms. Returns true when some reminder still has to go to To Do.
     */
    public boolean route(ActionLog log, long nowMs, boolean todoReady) {
        boolean push = false;
        for (int i = 0; i < log.entries.size(); i++) {
            ActionLog.Entry e = log.entries.get(i);
            if (!e.isReminder() || e.undone) {
                continue;
            }
            if (e.route.isEmpty()) {
                long at;
                try {
                    at = atMs(e);
                } catch (RuntimeException ex) {
                    e.route = "past";
                    continue;
                }
                if (at < nowMs - PAST_GRACE_MS) {
                    e.route = "past";
                } else if (!todoReady) {
                    e.route = "local";
                } else if (at - nowMs <= windowMs) {
                    e.route = "both";
                } else {
                    e.route = "todo";
                }
                if ("local".equals(e.route) || "both".equals(e.route)) {
                    ring(e, i, at);
                }
            }
            if (todoReady && needsPush(e)) {
                push = true;
            }
        }
        return push;
    }

    private static boolean needsPush(ActionLog.Entry e) {
        return e.todoTaskId.isEmpty() && !e.undone
                && ("todo".equals(e.route) || "both".equals(e.route) || "past".equals(e.route));
    }

    private void ring(ActionLog.Entry e, int index, long at) {
        if (e.alarmId == 0) {
            e.alarmId = alarmId(captureId, index);
        }
        alarms.schedule(e.alarmId, at, e.title, captureId);
    }

    /**
     * Network step: creates the To Do tasks. Throws on the first failure; what succeeded is already
     * in {@code log}, so the caller saves it and retries later.
     */
    public void push(ActionLog log, Todo todo, long nowMs) throws Exception {
        for (int i = 0; i < log.entries.size(); i++) {
            ActionLog.Entry e = log.entries.get(i);
            if (!e.isReminder() || !needsPush(e)) {
                continue;
            }
            long at = atMs(e);
            if ("todo".equals(e.route) && at - nowMs <= windowMs) {
                // The push came late (offline): the local alarm rings instead, To Do stays quiet.
                e.route = at < nowMs - PAST_GRACE_MS ? "past" : "both";
                if ("both".equals(e.route)) {
                    ring(e, i, at);
                }
            }
            String json = TodoClient.taskJson(e.title, e.notes, OffsetDateTime.parse(e.remindAt),
                    "todo".equals(e.route));
            e.todoListId = todo.listId();
            e.todoTaskId = todo.create(json);
        }
    }

    /**
     * To Do is unusable for good (signed out, list gone): every reminder that was waiting for To Do
     * rings locally instead. Returns how many were moved.
     */
    public int fallBackToLocal(ActionLog log, long nowMs) {
        int n = 0;
        for (int i = 0; i < log.entries.size(); i++) {
            ActionLog.Entry e = log.entries.get(i);
            if (e.isReminder() && !e.undone && "todo".equals(e.route) && e.todoTaskId.isEmpty()) {
                e.route = "local";
                long at = atMs(e);
                if (at >= nowMs - PAST_GRACE_MS) {
                    ring(e, i, at);
                }
                n++;
            }
        }
        return n;
    }

    /** All local alarms still due, e.g. to set them again after a reboot. */
    public void reschedule(ActionLog log, long nowMs) {
        for (int i = 0; i < log.entries.size(); i++) {
            ActionLog.Entry e = log.entries.get(i);
            if (e.isReminder() && !e.undone && e.alarmId != 0
                    && ("local".equals(e.route) || "both".equals(e.route))) {
                long at = atMs(e);
                if (at >= nowMs - PAST_GRACE_MS) {
                    alarms.schedule(e.alarmId, at, e.title, captureId);
                }
            }
        }
    }

    /**
     * Undo: cancels alarms and deletes To Do tasks. {@code todo} may be null (not signed in): then
     * tasks are left and counted. Throws on a network failure so the caller can retry.
     */
    public int undo(ActionLog log, Todo todo) throws Exception {
        int left = 0;
        for (ActionLog.Entry e : log.entries) {
            if (!e.isReminder() || "undone".equals(e.route)) {
                continue;
            }
            if (e.alarmId != 0) {
                alarms.cancel(e.alarmId);
            }
            if (!e.todoTaskId.isEmpty()) {
                if (todo == null) {
                    left++;
                    continue;
                }
                todo.delete(e.todoListId, e.todoTaskId);
            }
            e.route = "undone";
        }
        return left;
    }
}
