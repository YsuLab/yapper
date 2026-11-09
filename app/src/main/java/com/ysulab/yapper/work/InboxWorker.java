package com.ysulab.yapper.work;

import android.content.Context;
import android.content.pm.ServiceInfo;
import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.ysulab.yapper.R;
import com.ysulab.yapper.YapId;
import com.ysulab.yapper.capture.CaptureMeta;
import com.ysulab.yapper.capture.CaptureStore;
import com.ysulab.yapper.settings.Settings;
import com.ysulab.yapper.vault.InboxNote;
import com.ysulab.yapper.vault.VaultAccess;
import java.io.File;
import java.io.IOException;

/**
 * Copies a capture bundle into the vault: {@code Inbox/Capture <time>.md} plus audio and screenshot
 * in {@code Inbox/attachments/}. Needs no network. If the vault isn't chosen yet, it retries (with
 * backoff) until it is, and the bundle stays safe on the phone meanwhile.
 */
public final class InboxWorker extends Worker {

    public InboxWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        String id = getInputData().getString(CaptureScheduler.KEY_ID);
        if (id == null) {
            return Result.failure();
        }
        CaptureStore store = CaptureScheduler.store(ctx);
        CaptureMeta m;
        try {
            m = store.load(id);
        } catch (IOException e) {
            return Result.failure(); // no bundle: nothing to do
        }
        if (m.state.ordinal() >= CaptureMeta.State.INBOX.ordinal()) {
            CaptureScheduler.enqueueTranscribe(ctx, id);
            return Result.success();
        }

        VaultAccess vault = VaultAccess.open(ctx, new Settings(ctx).vaultUri());
        if (vault == null) {
            Notifications.needsSetup(ctx, ctx.getString(R.string.needs_vault));
            return Result.retry();
        }
        try {
            DocumentFile inbox = vault.dir(InboxNote.INBOX_DIR);
            DocumentFile attachments = vault.dir(InboxNote.INBOX_DIR, InboxNote.ATTACHMENTS_DIR);

            File audio = m.audioFile.isEmpty() ? null : store.file(id, m.audioFile);
            boolean hasAudio = audio != null && audio.isFile() && audio.length() > 0;
            if (hasAudio) {
                vault.copyIn(attachments, InboxNote.audioName(m), audio);
            }
            File shot = store.file(id, CaptureStore.SCREENSHOT);
            boolean hasShot = "saved".equals(m.screenshot) && shot.isFile();
            if (hasShot) {
                vault.copyIn(attachments, InboxNote.screenshotName(m), shot);
            }
            String screenText = store.readText(id, CaptureStore.SCREEN_TEXT);
            String name = InboxNote.fileName(m);
            vault.createNote(inbox, name, InboxNote.build(m, screenText, hasShot, hasAudio), YapId.marker(id));

            String path = InboxNote.INBOX_DIR + "/" + name;
            store.update(id, x -> {
                x.inboxPath = path;
                x.advanceTo(CaptureMeta.State.INBOX);
            });
            Notifications.clearNeedsSetup(ctx);
            Notifications.status(ctx, ctx.getString(R.string.saved_title), path);
        } catch (IOException | RuntimeException e) {
            return getRunAttemptCount() < 50 ? Result.retry() : Result.failure();
        }
        CaptureScheduler.enqueueTranscribe(ctx, id);
        return Result.success();
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        return foregroundInfo(getApplicationContext());
    }

    static ForegroundInfo foregroundInfo(Context ctx) {
        return new ForegroundInfo(Notifications.ID_WORK, Notifications.working(ctx),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }
}
