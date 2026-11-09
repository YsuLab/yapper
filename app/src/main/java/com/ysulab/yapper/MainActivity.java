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
import com.ysulab.yapper.transcribe.TranscriberConfig;
import com.ysulab.yapper.vault.VaultAccess;
import com.ysulab.yapper.work.CaptureScheduler;
import com.ysulab.yapper.work.Notifications;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Setup and status screen. Plain views on purpose: v1 has no polished UI (non-goal). */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 1;
    private static final int REQ_VAULT = 2;

    private Settings settings;
    private TextView status;
    private CheckBox compareAll;
    private EditText silence;
    private final List<Slot> slots = new ArrayList<>();

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
        compareAll = findViewById(R.id.compare_all);
        silence = findViewById(R.id.silence_seconds);

        ((Button) findViewById(R.id.btn_permissions)).setOnClickListener(v -> requestPerms());
        ((Button) findViewById(R.id.btn_assistant)).setOnClickListener(v -> openAssistantSettings());
        ((Button) findViewById(R.id.btn_vault)).setOnClickListener(v -> pickVault());
        ((Button) findViewById(R.id.btn_save)).setOnClickListener(v -> save());
        ((Button) findViewById(R.id.btn_retry)).setOnClickListener(v -> {
            new Thread(() -> CaptureScheduler.reconcile(getApplicationContext())).start();
            Toast.makeText(this, R.string.retry_queued, Toast.LENGTH_SHORT).show();
        });

        buildSlots(findViewById(R.id.transcribers));
        compareAll.setChecked(settings.compareAll());
        silence.setText(String.format(Locale.ROOT, "%.1f", settings.silenceMs() / 1000.0));
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
            label.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium);
            label.setPadding(0, dp(16), 0, 0);
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
        try {
            settings.setSilenceMs(Math.round(Double.parseDouble(silence.getText().toString().trim()) * 1000));
        } catch (NumberFormatException ignored) {
            // keep previous value
        }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        new Thread(() -> CaptureScheduler.reconcile(getApplicationContext())).start();
        refreshStatus();
    }

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        boolean assistant = VoiceInteractionService.isActiveService(this,
                new ComponentName(this, YapperAssistService.class));
        sb.append(check(assistant)).append(getString(R.string.st_assistant)).append('\n');
        sb.append(check(granted(Manifest.permission.RECORD_AUDIO))).append(getString(R.string.st_mic)).append('\n');
        if (Build.VERSION.SDK_INT >= 33) {
            sb.append(check(granted(Manifest.permission.POST_NOTIFICATIONS)))
                    .append(getString(R.string.st_notifications)).append('\n');
        }
        VaultAccess vault = VaultAccess.open(this, settings.vaultUri());
        sb.append(check(vault != null)).append(getString(R.string.st_vault))
                .append(vault != null ? ": " + vault.name() : "").append('\n');
        List<TranscriberConfig> active = settings.activeTranscribers();
        sb.append(check(!active.isEmpty())).append(getString(R.string.st_transcribers)).append(": ");
        if (active.isEmpty()) {
            sb.append(getString(R.string.st_none));
        } else {
            for (int i = 0; i < active.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(TranscriberConfig.displayName(active.get(i).id));
            }
        }
        int[] c = CaptureScheduler.counts(this);
        sb.append("\n\n").append(getString(R.string.st_captures, c[CaptureMeta.State.DONE.ordinal()]
                        + c[CaptureMeta.State.INBOX.ordinal()],
                c[CaptureMeta.State.RECORDED.ordinal()] + c[CaptureMeta.State.RECORDING.ordinal()],
                c[CaptureMeta.State.INBOX.ordinal()]));
        status.setText(sb.toString());
    }

    private static String check(boolean ok) {
        return ok ? "✅ " : "⬜ ";
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
            new Thread(() -> CaptureScheduler.reconcile(getApplicationContext())).start();
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
