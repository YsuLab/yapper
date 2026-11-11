package com.ysulab.yapper;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.service.voice.VoiceInteractionService;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.ysulab.yapper.assist.YapperAssistService;
import com.ysulab.yapper.capture.CaptureMeta;
import com.ysulab.yapper.settings.Settings;
import com.ysulab.yapper.sort.SorterConfig;
import com.ysulab.yapper.transcribe.TranscriberConfig;
import com.ysulab.yapper.vault.VaultAccess;
import com.ysulab.yapper.work.CaptureScheduler;
import com.ysulab.yapper.work.Notifications;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Setup, status and settings screen, in the Yapper identity (dither header, status rows, flat controls). */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 1;
    private static final int REQ_VAULT = 2;

    private Settings settings;
    private TextView status;
    private LinearLayout statusRows;
    private TextView homeReadout;
    private android.view.View homeDot;
    private com.ysulab.yapper.ui.DitherView homeField;
    private CheckBox compareAll;
    private EditText silence;
    private final List<Slot> slots = new ArrayList<>();
    private EditText sorterUrl;
    private EditText sorterModel;
    private EditText sorterKey;
    private CheckBox sorterShadow;
    private CheckBox sorterMove;
    private CheckBox sorterScreens;
    private EditText msClientId;
    private EditText alarmWindow;
    private EditText nudgeMorning;
    private EditText nudgeEvening;
    private com.ysulab.yapper.reminders.MsAccount ms;

    private static final class Slot {
        TranscriberConfig cfg;
        EditText key;
        EditText model;
        EditText url;
        EditText language;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        settings = new Settings(this);
        Notifications.ensureChannels(this);

        TextView buildInfo = findViewById(R.id.build_info);
        buildInfo.setText(BuildInfo.describe(
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE));
        status = findViewById(R.id.status);
        statusRows = findViewById(R.id.status_rows);
        homeReadout = findViewById(R.id.home_readout);
        homeDot = findViewById(R.id.home_dot);
        homeField = findViewById(R.id.home_field);
        com.ysulab.yapper.ui.SmokeScene scene = homeField.scene();
        scene.moonY = 0.5f;
        scene.moonR = 0.17f;
        scene.quietTop = 0.2f;
        scene.quietBottom = 0.2f;
        homeField.showStill(1f);
        compareAll = findViewById(R.id.compare_all);
        silence = findViewById(R.id.silence_seconds);

        ((Button) findViewById(R.id.btn_save)).setOnClickListener(v -> save());
        ((Button) findViewById(R.id.btn_retry)).setOnClickListener(v -> {
            new Thread(() -> CaptureScheduler.reconcile(getApplicationContext(), true)).start();
            Toast.makeText(this, R.string.retry_queued, Toast.LENGTH_SHORT).show();
        });

        buildSlots(findViewById(R.id.transcribers));
        compareAll.setChecked(settings.compareAll());
        silence.setText(String.format(Locale.ROOT, "%.1f", settings.silenceMs() / 1000.0));

        SorterConfig sc = settings.sorter();
        sorterUrl = findViewById(R.id.sorter_url);
        sorterModel = findViewById(R.id.sorter_model);
        sorterKey = findViewById(R.id.sorter_key);
        sorterShadow = findViewById(R.id.sorter_shadow);
        sorterMove = findViewById(R.id.sorter_move);
        sorterScreens = findViewById(R.id.sorter_screens);
        sorterUrl.setText(sc.baseUrl);
        sorterModel.setText(sc.model);
        sorterKey.setHint(settings.hasOwnSorterKey() ? R.string.hint_key_saved : R.string.hint_key);
        sorterShadow.setChecked(sc.shadow);
        sorterMove.setChecked(sc.moveSorted);
        sorterScreens.setChecked(sc.screenshots);

        // M4/M5: talk buttons
        ((Button) findViewById(R.id.btn_ramble)).setOnClickListener(v -> ramble("ramble"));
        ((Button) findViewById(R.id.btn_plan)).setOnClickListener(v -> ramble("plan"));
        ((Button) findViewById(R.id.btn_debrief)).setOnClickListener(v -> ramble("debrief"));

        // M3: Microsoft To Do
        ms = new com.ysulab.yapper.reminders.MsAccount(this);
        msClientId = findViewById(R.id.ms_client_id);
        msClientId.setText(ms.clientId());
        alarmWindow = findViewById(R.id.alarm_window);
        alarmWindow.setText(String.valueOf(settings.alarmWindowMin()));
        ((Button) findViewById(R.id.btn_ms_sign_in)).setOnClickListener(v -> {
            ms.setClientId(msClientId.getText().toString());
            startActivity(new Intent(this, com.ysulab.yapper.reminders.AuthActivity.class)
                    .putExtra(com.ysulab.yapper.reminders.AuthActivity.EXTRA_START, true));
        });
        ((Button) findViewById(R.id.btn_ms_list)).setOnClickListener(v ->
                startActivity(new Intent(this, com.ysulab.yapper.reminders.AuthActivity.class)
                        .putExtra(com.ysulab.yapper.reminders.AuthActivity.EXTRA_PICK_LIST, true)));
        ((Button) findViewById(R.id.btn_ms_sign_out)).setOnClickListener(v -> {
            ms.signOut();
            refreshStatus();
        });
        ((Button) findViewById(R.id.btn_ms_test)).setOnClickListener(v -> testTodo());

        // M5: nudges
        nudgeMorning = findViewById(R.id.nudge_morning);
        nudgeEvening = findViewById(R.id.nudge_evening);
        nudgeMorning.setText(hhmm(settings.morningNudgeMin()));
        nudgeEvening.setText(hhmm(settings.eveningNudgeMin()));
        com.ysulab.yapper.today.Nudges.scheduleAll(this);
    }

    private void ramble(String mode) {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            requestPerms();
            return;
        }
        if (com.ysulab.yapper.assist.RambleService.isRunning()) {
            startService(new Intent(this, com.ysulab.yapper.assist.RambleService.class)
                    .setAction(com.ysulab.yapper.assist.RambleService.ACTION_STOP));
            Toast.makeText(this, R.string.ramble_saved, Toast.LENGTH_SHORT).show();
        } else {
            startForegroundService(com.ysulab.yapper.assist.RambleService.startIntent(this, mode));
            Toast.makeText(this, R.string.ramble_started, Toast.LENGTH_SHORT).show();
        }
        new android.os.Handler(getMainLooper()).postDelayed(this::refreshStatus, 500);
    }

    /** Creates a To Do task with a reminder in 3 minutes, to measure Samsung Reminder sync delay. */
    private void testTodo() {
        if (!ms.isReady()) {
            Toast.makeText(this, R.string.st_todo_off, Toast.LENGTH_LONG).show();
            return;
        }
        java.time.OffsetDateTime at = java.time.OffsetDateTime.now().plusMinutes(3).withSecond(0).withNano(0);
        new Thread(() -> {
            String msg;
            try {
                ms.todo().create(com.ysulab.yapper.reminders.TodoClient.taskJson(
                        "Yapper sync test " + at.toLocalTime(), "Sent at " + java.time.LocalTime.now().withNano(0),
                        at, true));
                msg = getString(R.string.ms_test_sent, at.toLocalTime().toString());
            } catch (Exception e) {
                msg = getString(R.string.ms_test_failed, e.getMessage());
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    static String hhmm(int minuteOfDay) {
        return minuteOfDay < 0 ? "" : String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    /** "8:00" → 480; empty → -1 (off); invalid → {@code fallback}. */
    static int parseHhmm(String s, int fallback) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})[:.](\\d{2})").matcher(t);
        if (!m.matches()) {
            return fallback;
        }
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        return h < 24 && min < 60 ? h * 60 + min : fallback;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        new Thread(() -> CaptureScheduler.reconcile(getApplicationContext())).start();
    }

    private void buildSlots(LinearLayout container) {
        for (TranscriberConfig cfg : settings.allTranscribers()) {
            Slot s = new Slot();
            s.cfg = cfg;
            TextView label = new TextView(this);
            label.setText(TranscriberConfig.displayName(cfg.id));
            label.setTextAppearance(R.style.Yapper_Body);
            label.setTypeface(android.graphics.Typeface.create(getResources().getFont(R.font.yapper_sans), 600, false));
            label.setPadding(0, dp(20), 0, 0);
            container.addView(label);
            if (TranscriberConfig.SELF_HOSTED.equals(cfg.id)) {
                s.url = field(container, getString(R.string.hint_url), cfg.baseUrl, false);
            }
            boolean hasKey = !cfg.apiKey.isEmpty();
            s.key = field(container, getString(hasKey ? R.string.hint_key_saved : R.string.hint_key), "", true);
            s.model = field(container, getString(R.string.hint_model), cfg.model, false);
            s.language = field(container, getString(R.string.hint_language), cfg.language, false);
            slots.add(s);
        }
    }

    private EditText field(LinearLayout parent, String hint, String value, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        parent.addView(e, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private void save() {
        for (Slot s : slots) {
            if (s.url != null) {
                s.cfg.baseUrl = s.url.getText().toString().trim();
            }
            s.cfg.model = s.model.getText().toString().trim();
            s.cfg.language = s.language.getText().toString().trim();
            String key = s.key.getText().toString().trim();
            // Empty field = keep the saved key. Type "-" to delete it.
            settings.saveTranscriber(s.cfg, key.isEmpty() ? null : ("-".equals(key) ? "" : key));
            s.key.setText("");
            s.key.setHint(getString(settings.transcriber(s.cfg.id).apiKey.isEmpty()
                    ? R.string.hint_key : R.string.hint_key_saved));
        }
        settings.setCompareAll(compareAll.isChecked());
        SorterConfig sc = settings.sorter();
        sc.baseUrl = sorterUrl.getText().toString().trim();
        sc.model = sorterModel.getText().toString().trim();
        sc.shadow = sorterShadow.isChecked();
        sc.moveSorted = sorterMove.isChecked();
        sc.screenshots = sorterScreens.isChecked();
        String sk = sorterKey.getText().toString().trim();
        settings.saveSorter(sc, sk.isEmpty() ? null : ("-".equals(sk) ? "" : sk));
        sorterKey.setText("");
        sorterKey.setHint(settings.hasOwnSorterKey() ? R.string.hint_key_saved : R.string.hint_key);
        ms.setClientId(msClientId.getText().toString());
        try {
            settings.setAlarmWindowMin(Integer.parseInt(alarmWindow.getText().toString().trim()));
        } catch (NumberFormatException ignored) {
            // keep previous value
        }
        settings.setNudges(parseHhmm(nudgeMorning.getText().toString(), settings.morningNudgeMin()),
                parseHhmm(nudgeEvening.getText().toString(), settings.eveningNudgeMin()));
        com.ysulab.yapper.today.Nudges.scheduleAll(this);
        try {
            settings.setSilenceMs(Math.round(Double.parseDouble(silence.getText().toString().trim()) * 1000));
        } catch (NumberFormatException ignored) {
            // keep previous value
        }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        new Thread(() -> CaptureScheduler.reconcile(getApplicationContext())).start();
        refreshStatus();
    }

    private enum Mark { READY, NEEDS_YOU, OFF, LIVE }

    private void refreshStatus() {
        statusRows.removeAllViews();
        int todo = 0;
        boolean assistant = VoiceInteractionService.isActiveService(this,
                new ComponentName(this, YapperAssistService.class));
        todo += row(getString(R.string.st_assistant), null, assistant ? Mark.READY : Mark.NEEDS_YOU,
                v -> openAssistantSettings());
        boolean mic = granted(Manifest.permission.RECORD_AUDIO);
        todo += row(getString(R.string.st_mic), null, mic ? Mark.READY : Mark.NEEDS_YOU, v -> requestPerms());
        if (Build.VERSION.SDK_INT >= 33) {
            boolean notif = granted(Manifest.permission.POST_NOTIFICATIONS);
            todo += row(getString(R.string.st_notifications), null, notif ? Mark.READY : Mark.NEEDS_YOU,
                    v -> requestPerms());
        }
        VaultAccess vault = VaultAccess.open(this, settings.vaultUri());
        todo += row(getString(R.string.st_vault), vault != null ? vault.name() : null,
                vault != null ? Mark.READY : Mark.NEEDS_YOU, v -> pickVault());
        List<com.ysulab.yapper.transcribe.Transcriber> active = settings.transcribers();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < active.size(); i++) {
            names.append(i == 0 ? "" : ", ").append(active.get(i).label());
        }
        todo += row(getString(R.string.st_transcribers), active.isEmpty() ? getString(R.string.st_none) : names.toString(),
                active.isEmpty() ? Mark.NEEDS_YOU : Mark.READY, null);
        SorterConfig sc = settings.sorter();
        row(getString(R.string.st_sorter), sc.isConfigured()
                        ? sc.modelId() + (sc.shadow ? " (preview only)" : "") : getString(R.string.st_sorter_off),
                sc.isConfigured() ? Mark.READY : Mark.OFF, null);
        row(getString(R.string.st_todo), ms.isReady() ? ms.listName() : getString(R.string.st_todo_off),
                ms.isReady() ? Mark.READY : Mark.OFF, null);
        todo += row(getString(R.string.st_exact), null,
                com.ysulab.yapper.reminders.LocalAlarms.exactAllowed(this) ? Mark.READY : Mark.NEEDS_YOU, null);
        boolean rambling = com.ysulab.yapper.assist.RambleService.isRunning();
        if (rambling) {
            row(getString(R.string.st_ramble), null, Mark.LIVE, null);
        }

        int[] c = CaptureScheduler.counts(this);
        int[] so = CaptureScheduler.sortCounts(this);
        status.setText(getString(R.string.st_captures, c[CaptureMeta.State.DONE.ordinal()]
                        + c[CaptureMeta.State.INBOX.ordinal()],
                c[CaptureMeta.State.RECORDED.ordinal()] + c[CaptureMeta.State.RECORDING.ordinal()],
                c[CaptureMeta.State.INBOX.ordinal()])
                + "\n" + getString(R.string.st_sorted, so[0], so[1], so[2], so[3]));

        // Header: smoke keeps rising while a ramble records; otherwise it rests.
        if (rambling) {
            homeReadout.setText(R.string.ramble_title);
            homeDot.setVisibility(android.view.View.VISIBLE);
            homeField.press();
        } else {
            homeReadout.setText(todo == 0 ? getString(R.string.home_readout_idle)
                    : getString(R.string.home_readout_setup, todo));
            homeDot.setVisibility(todo == 0 ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
            homeField.release();
        }
    }

    /** Adds one status row; returns 1 when it needs the owner, so the header can count them. */
    private int row(String title, String sub, Mark mark, android.view.View.OnClickListener onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setBackgroundResource(R.drawable.row);
        r.setMinimumHeight(dp(56));
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setTextAppearance(R.style.Yapper_Body);
        t.setText(title);
        text.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView st = new TextView(this);
            st.setTextAppearance(R.style.Yapper_Readout_Muted);
            st.setText(sub);
            st.setSingleLine(true);
            st.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(st);
        }
        r.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView m = new TextView(this);
        m.setTextAppearance(R.style.Yapper_Readout);
        int color;
        int word;
        switch (mark) {
            case READY: color = R.color.yapper_success; word = R.string.st_ready; break;
            case NEEDS_YOU: color = R.color.yapper_gold; word = R.string.st_needs_you; break;
            case LIVE: color = R.color.yapper_primary_text; word = R.string.st_on; break;
            default: color = R.color.yapper_muted; word = R.string.st_off; break;
        }
        m.setText(word);
        m.setTextColor(getColor(color));
        m.setPadding(dp(12), 0, 0, 0);
        r.addView(m);
        if (onClick != null) {
            r.setOnClickListener(onClick);
            r.setClickable(true);
            r.setFocusable(true);
        }
        r.setContentDescription(title + ", " + getString(word));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = statusRows.getChildCount() == 0 ? 0 : dp(8);
        statusRows.addView(r, lp);
        return mark == Mark.NEEDS_YOU ? 1 : 0;
    }

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerms() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(perms.toArray(new String[0]), REQ_PERMS);
    }

    private void openAssistantSettings() {
        // The assistant role can't be requested by an app; send the owner to the picker.
        String[] actions = {
            android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS,
            android.provider.Settings.ACTION_SETTINGS
        };
        for (String a : actions) {
            try {
                startActivity(new Intent(a));
                return;
            } catch (ActivityNotFoundException ignored) {
                // try the next one
            }
        }
    }

    private void pickVault() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_VAULT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VAULT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            settings.setVaultUri(uri);
            Notifications.clearNeedsSetup(this);
            new Thread(() -> {
                CaptureScheduler.ensureRules(getApplicationContext());
                CaptureScheduler.reconcile(getApplicationContext());
            }).start();
            refreshStatus();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshStatus();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
