package com.ysulab.yapper.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import com.ysulab.yapper.transcribe.TranscriberConfig;
import java.util.ArrayList;
import java.util.List;

/** Non-secret preferences plus access to transcriber configs (keys via {@link SecretStore}). */
public final class Settings {

    private static final String PREFS = "settings";
    public static final String ZONE = "Asia/Riyadh";

    private final SharedPreferences prefs;
    private final SecretStore secrets;

    public Settings(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        secrets = new SecretStore(app);
    }

    public Uri vaultUri() {
        String s = prefs.getString("vault_uri", null);
        return s == null ? null : Uri.parse(s);
    }

    public void setVaultUri(Uri uri) {
        prefs.edit().putString("vault_uri", uri == null ? null : uri.toString()).apply();
    }

    /** Silence that ends a quick capture, in ms. Default 2 s. */
    public long silenceMs() {
        return prefs.getLong("silence_ms", 2000);
    }

    public void setSilenceMs(long ms) {
        prefs.edit().putLong("silence_ms", Math.max(800, Math.min(10_000, ms))).apply();
    }

    /** No speech at all for this long → save screen-only capture. */
    public long noSpeechMs() {
        return prefs.getLong("no_speech_ms", 6000);
    }

    /** Hard cap for a quick capture (ramble mode is M4). */
    public long maxCaptureMs() {
        return 5 * 60_000L;
    }

    /** M1 benchmark: run every configured transcriber on every capture. */
    public boolean compareAll() {
        return prefs.getBoolean("compare_all", true);
    }

    public void setCompareAll(boolean on) {
        prefs.edit().putBoolean("compare_all", on).apply();
    }

    public TranscriberConfig transcriber(String id) {
        TranscriberConfig c = new TranscriberConfig(id);
        c.baseUrl = prefs.getString("tx_" + id + "_url", c.baseUrl);
        c.model = prefs.getString("tx_" + id + "_model", c.model);
        c.language = prefs.getString("tx_" + id + "_lang", c.language);
        c.prompt = prefs.getString("tx_" + id + "_prompt", c.prompt);
        c.apiKey = secrets.get("tx_" + id + "_key");
        return c;
    }

    /** Saves a slot. A null key leaves the stored key unchanged; "" clears it. */
    public void saveTranscriber(TranscriberConfig c, String newKeyOrNull) {
        prefs.edit()
                .putString("tx_" + c.id + "_url", c.baseUrl)
                .putString("tx_" + c.id + "_model", c.model)
                .putString("tx_" + c.id + "_lang", c.language)
                .putString("tx_" + c.id + "_prompt", c.prompt)
                .apply();
        if (newKeyOrNull != null) {
            secrets.put("tx_" + c.id + "_key", newKeyOrNull.trim());
        }
    }

    public List<TranscriberConfig> allTranscribers() {
        List<TranscriberConfig> out = new ArrayList<>();
        for (String id : TranscriberConfig.ALL) {
            out.add(transcriber(id));
        }
        return out;
    }

    public List<TranscriberConfig> activeTranscribers() {
        return TranscriberConfig.active(allTranscribers(), compareAll());
    }
}
