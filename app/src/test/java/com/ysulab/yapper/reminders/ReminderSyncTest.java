package com.ysulab.yapper.reminders;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ysulab.yapper.sort.ActionLog;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ReminderSyncTest {

    static final String ID = "20261109-113000-ab12";
    static final long NOW = OffsetDateTime.parse("2026-11-09T14:00:00+03:00").toInstant().toEpochMilli();
    static final long HOUR = 3600_000L;

    static final class FakeAlarms implements ReminderSync.Alarms {
        final Map<Integer, Long> set = new HashMap<>();

        @Override
        public void schedule(int id, long atMs, String title, String captureId) {
            set.put(id, atMs);
        }

        @Override
        public void cancel(int id) {
            set.remove(id);
        }
    }

    static final class FakeTodo implements ReminderSync.Todo {
        final List<String> created = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        boolean fail;

        @Override
        public String create(String json) throws Exception {
            if (fail) throw new java.io.IOException("offline");
            created.add(json);
            return "task" + created.size();
        }

        @Override
        public void delete(String listId, String taskId) throws Exception {
            if (fail) throw new java.io.IOException("offline");
            deleted.add(listId + "/" + taskId);
        }

        @Override
        public String listId() {
            return "L1";
        }
    }

    static ActionLog log(String... times) {
        ActionLog log = new ActionLog();
        log.add("append", "Uni/OS.md", "text", "append_to_note");
        for (String t : times) {
            ActionLog.Entry e = log.add("append", "Daily/2026-11-09.md", "- [ ] ⏰ " + t, "create_reminder");
            e.title = "R " + t;
            e.remindAt = t;
        }
        return log;
    }

    @Test
    public void routesByTimeAndSetup() throws Exception {
        ActionLog log = log("2026-11-09T14:20:00+03:00",   // in 20 min → both
                "2026-11-09T20:00:00+03:00",               // later → todo
                "2026-11-09T10:00:00+03:00");              // past → past
        FakeAlarms alarms = new FakeAlarms();
        ReminderSync s = new ReminderSync(ID, alarms, HOUR);
        assertTrue(s.route(log, NOW, true));
        assertEquals("", log.entries.get(0).route);
        assertEquals("both", log.entries.get(1).route);
        assertEquals("todo", log.entries.get(2).route);
        assertEquals("past", log.entries.get(3).route);
        assertEquals(1, alarms.set.size());
        assertEquals(NOW + 20 * 60_000L, (long) alarms.set.get(log.entries.get(1).alarmId));

        FakeTodo todo = new FakeTodo();
        s.push(log, todo, NOW);
        assertEquals(3, todo.created.size());
        // "both": To Do task stays quiet, so Samsung Reminder does not ring twice.
        assertTrue(todo.created.get(0).contains("\"isReminderOn\":false"));
        assertTrue(todo.created.get(1).contains("\"isReminderOn\":true"));
        assertTrue(todo.created.get(1).contains("2026-11-09T17:00:00.0000000"));
        assertEquals("task2", log.entries.get(2).todoTaskId);
        assertEquals("L1", log.entries.get(2).todoListId);

        // Second push does nothing.
        s.push(log, todo, NOW);
        assertEquals(3, todo.created.size());
        assertFalse(s.route(log, NOW, true));
    }

    @Test
    public void withoutTodoEverythingRingsLocally() {
        ActionLog log = log("2026-11-09T14:20:00+03:00", "2026-11-12T09:00:00+03:00");
        FakeAlarms alarms = new FakeAlarms();
        assertFalse(new ReminderSync(ID, alarms, HOUR).route(log, NOW, false));
        assertEquals("local", log.entries.get(1).route);
        assertEquals("local", log.entries.get(2).route);
        assertEquals(2, alarms.set.size());
    }

    @Test
    public void latePushHandsOverToTheLocalAlarm() throws Exception {
        ActionLog log = log("2026-11-09T20:00:00+03:00");
        FakeAlarms alarms = new FakeAlarms();
        ReminderSync s = new ReminderSync(ID, alarms, HOUR);
        s.route(log, NOW, true);
        assertEquals("todo", log.entries.get(1).route);
        // Offline until 19:30: the push now happens inside the window.
        FakeTodo todo = new FakeTodo();
        s.push(log, todo, NOW + 5 * HOUR + 30 * 60_000L);
        assertEquals("both", log.entries.get(1).route);
        assertEquals(1, alarms.set.size());
        assertTrue(todo.created.get(0).contains("\"isReminderOn\":false"));
    }

    @Test
    public void failedPushKeepsProgressAndFallsBack() {
        ActionLog log = log("2026-11-09T20:00:00+03:00", "2026-11-10T08:00:00+03:00");
        FakeAlarms alarms = new FakeAlarms();
        ReminderSync s = new ReminderSync(ID, alarms, HOUR);
        s.route(log, NOW, true);
        FakeTodo todo = new FakeTodo();
        todo.fail = true;
        try {
            s.push(log, todo, NOW);
        } catch (Exception expected) {
            // ok
        }
        assertEquals(2, s.fallBackToLocal(log, NOW));
        assertEquals("local", log.entries.get(1).route);
        assertEquals(2, alarms.set.size());
    }

    @Test
    public void undoCancelsAlarmsAndDeletesTasks() throws Exception {
        ActionLog log = log("2026-11-09T14:20:00+03:00", "2026-11-09T20:00:00+03:00");
        FakeAlarms alarms = new FakeAlarms();
        ReminderSync s = new ReminderSync(ID, alarms, HOUR);
        s.route(log, NOW, true);
        FakeTodo todo = new FakeTodo();
        s.push(log, todo, NOW);
        assertEquals(1, alarms.set.size());

        todo.fail = true;
        try {
            s.undo(log, todo);
            org.junit.Assert.fail("expected a network error");
        } catch (Exception expected) {
            // retried later
        }
        todo.fail = false;
        assertEquals(0, s.undo(log, todo));
        assertEquals(0, alarms.set.size());
        assertEquals(2, todo.deleted.size());
        assertEquals("undone", log.entries.get(1).route);
        // Not signed in: tasks are counted as left.
        ActionLog other = log("2026-11-09T20:00:00+03:00");
        s.route(other, NOW, true);
        s.push(other, todo, NOW);
        assertEquals(1, s.undo(other, null));
    }

    @Test
    public void rescheduleSetsOnlyFutureLocalAlarms() {
        ActionLog log = log("2026-11-09T14:20:00+03:00", "2026-11-09T14:40:00+03:00");
        ReminderSync s = new ReminderSync(ID, new FakeAlarms(), HOUR);
        s.route(log, NOW, false);
        FakeAlarms after = new FakeAlarms();
        new ReminderSync(ID, after, HOUR).reschedule(log, NOW + 30 * 60_000L);
        assertEquals(1, after.set.size());
    }

    @Test
    public void logRoundTripKeepsReminderState() throws Exception {
        ActionLog log = log("2026-11-09T14:20:00+03:00");
        new ReminderSync(ID, new FakeAlarms(), HOUR).route(log, NOW, false);
        ActionLog back = ActionLog.parse(log.serialize());
        assertEquals("local", back.entries.get(1).route);
        assertEquals(log.entries.get(1).alarmId, back.entries.get(1).alarmId);
        assertTrue(back.entries.get(1).alarmId != 0);
    }
}
