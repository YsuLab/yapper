package com.ysulab.yapper.work;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.ysulab.yapper.hub.DoneBlock;
import com.ysulab.yapper.hub.HubClient;
import com.ysulab.yapper.settings.Settings;
import com.ysulab.yapper.sort.Executor;
import com.ysulab.yapper.vault.SafVaultFs;
import com.ysulab.yapper.vault.VaultAccess;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Nightly: tasks struck on the board go into that day's daily note as one "## Done today" block
 * (decision 5). One way only: the vault never changes the board. Each day is written once (marker).
 * Runs about 23:30; it also catches up yesterday if the phone was off.
 */
public final class DoneWorker extends Worker {

    public DoneWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        Settings settings = new Settings(ctx);
        HubClient hub = settings.hub();
        if (!hub.isConfigured()) {
            return Result.success();
        }
        VaultAccess vault = VaultAccess.open(ctx, settings.vaultUri());
        if (vault == null) {
            return Result.success();
        }
        SafVaultFs fs = new SafVaultFs(vault);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(Settings.ZONE));
        try {
            LocalDate today = now.toLocalDate();
            write(fs, hub, today.minusDays(1).toString());
            if (now.getHour() >= 23) {
                write(fs, hub, today.toString());
            }
            return Result.success();
        } catch (IOException e) {
            return getRunAttemptCount() < 5 ? Result.retry() : Result.success();
        } catch (Exception e) {
            return Result.success();
        }
    }

    static void write(SafVaultFs fs, HubClient hub, String day) throws Exception {
        String path = Executor.dailyPath(day);
        String current = fs.read(path);
        if (current != null && current.contains(DoneBlock.marker(day))) {
            return;
        }
        HubClient.Response r = hub.doneOn(day);
        if (!r.ok()) {
            throw new IOException("hub " + r.code);
        }
        String block = DoneBlock.build(day, r.body);
        if (block == null) {
            return;
        }
        current = fs.read(path); // re-read right before appending
        if (current == null) {
            fs.create(path, "# " + day + "\n" + block);
        } else if (!current.contains(DoneBlock.marker(day))) {
            fs.append(path, (current.endsWith("\n") ? "" : "\n") + block);
        }
    }
}
