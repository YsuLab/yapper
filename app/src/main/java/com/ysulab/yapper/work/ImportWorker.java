package com.ysulab.yapper.work;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.ysulab.yapper.hub.HubClient;
import com.ysulab.yapper.hub.VaultTasks;
import com.ysulab.yapper.settings.Settings;
import com.ysulab.yapper.vault.SafVaultFs;
import com.ysulab.yapper.vault.VaultAccess;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * Decision 6: reads every note, sends the open "- [ ]" tasks to the hub once, and leaves the vault
 * untouched. Safe to run twice: task ids come from note + line, so nothing is added again.
 */
public final class ImportWorker extends Worker {

    public ImportWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        Settings settings = new Settings(ctx);
        HubClient hub = settings.hub();
        VaultAccess vault = VaultAccess.open(ctx, settings.vaultUri());
        if (!hub.isConfigured() || vault == null) {
            Notifications.status(ctx, "Import not started", "Set the vault folder and the phone token first.");
            return Result.success();
        }
        try {
            SafVaultFs fs = new SafVaultFs(vault);
            Map<String, String> notes = new LinkedHashMap<>();
            for (String path : fs.listNotes().keySet()) {
                if (!path.startsWith("_yapper/")) {
                    notes.put(path, fs.read(path));
                }
            }
            List<VaultTasks.Found> found = VaultTasks.find(notes);
            String at = ZonedDateTime.now(ZoneId.of(Settings.ZONE)).withNano(0)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            int added = 0;
            for (JSONObject p : VaultTasks.payloads(found, at)) {
                HubClient.Response r = hub.postCapture(p.toString());
                if (!r.ok()) {
                    if (HubClient.retryable(r.code) && getRunAttemptCount() < 5) {
                        return Result.retry();
                    }
                    Notifications.status(ctx, "Import stopped", "The hub answered HTTP " + r.code + ".");
                    return Result.success();
                }
                org.json.JSONArray a = new JSONObject(r.body).optJSONArray("added");
                added += a == null ? 0 : a.length();
            }
            Notifications.status(ctx, "Vault tasks sent to the board",
                    found.size() + " open tasks found, " + added + " new on the board. The vault was not changed.");
            return Result.success();
        } catch (java.io.IOException e) {
            return getRunAttemptCount() < 5 ? Result.retry() : Result.success();
        } catch (Exception e) {
            Notifications.status(ctx, "Import failed", e.getClass().getSimpleName());
            return Result.success();
        }
    }
}
