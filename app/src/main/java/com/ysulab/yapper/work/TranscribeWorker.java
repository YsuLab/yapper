package com.ysulab.yapper.work;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.ysulab.yapper.R;
import com.ysulab.yapper.capture.CaptureMeta;
import com.ysulab.yapper.capture.CaptureStore;
import com.ysulab.yapper.settings.Settings;
import com.ysulab.yapper.transcribe.Transcriber;
import com.ysulab.yapper.transcribe.TranscriptionException;
import com.ysulab.yapper.vault.InboxNote;
import com.ysulab.yapper.vault.VaultAccess;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Runs each active transcriber once on a capture and appends its transcript (with latency) to the
 * Inbox note. In compare mode that is every configured provider, which is the M1 benchmark data.
 * Progress is saved per provider, so a retry never appends the same transcript twice.
 */
public final class TranscribeWorker extends Worker {

    /** After this many attempts a retryable error is written down as a failure and we move on. */
    static final int MAX_ATTEMPTS = 25;

    public TranscribeWorker(@NonNull Context context, @NonNull WorkerParameters params) {
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
        Settings settings = new Settings(ctx);
        CaptureMeta m;
        try {
            m = store.load(id);
        } catch (IOException e) {
            return Result.failure();
        }
        if (m.state != CaptureMeta.State.INBOX) {
            return Result.success();
        }
        File audio = m.audioFile.isEmpty() ? null : store.file(id, m.audioFile);
        List<Transcriber> active = settings.transcribers();
        if (audio == null || !audio.isFile() || audio.length() == 0 || active.isEmpty()) {
            // Nothing to transcribe, or no provider yet. reconcile() re-queues when a key is added.
            return Result.success();
        }
        VaultAccess vault = VaultAccess.open(ctx, settings.vaultUri());
        if (vault == null) {
            return Result.retry();
        }

        boolean needRetry = false;
        String lastText = null;
        for (Transcriber t : active) {
            if (m.transcribedBy.contains(t.id())) {
                continue;
            }
            String block;
            try {
                Transcriber.Transcript tr = t.transcribe(audio);
                block = InboxNote.transcriptBlock(id, t.label(), tr.latencyMs, tr.text);
                lastText = tr.text;
                // Kept in the bundle for the sorter (M2), before the note, so a retry has it too.
                store.writeText(id, SortWorker.transcriptFile(t.id()), tr.text == null ? "" : tr.text);
            } catch (IOException e) {
                return Result.retry();
            } catch (TranscriptionException e) {
                if (e.retryable && getRunAttemptCount() < MAX_ATTEMPTS) {
                    needRetry = true;
                    continue;
                }
                block = InboxNote.transcriptFailedBlock(id, t.label(), e.getMessage());
            }
            try {
                appendToNote(vault, m, block);
                m = store.update(id, x -> x.transcribedBy.add(t.id()));
            } catch (IOException | RuntimeException e) {
                return Result.retry();
            }
        }
        if (needRetry) {
            return Result.retry();
        }
        try {
            store.update(id, x -> x.advanceTo(CaptureMeta.State.DONE));
        } catch (IOException e) {
            return Result.retry();
        }
        if (settings.sorter().isConfigured()) {
            // The sorter's result notification replaces the transcript one.
            CaptureScheduler.enqueueSort(ctx, id);
        } else if (lastText != null) {
            String preview = lastText.length() > 120 ? lastText.substring(0, 120) + "…" : lastText;
            Notifications.status(ctx, ctx.getString(R.string.transcribed_title), preview);
        }
        return Result.success();
    }

    private static void appendToNote(VaultAccess vault, CaptureMeta m, String block) throws IOException {
        String path = m.inboxPath.isEmpty() ? InboxNote.INBOX_DIR + "/" + InboxNote.fileName(m) : m.inboxPath;
        String[] parts = path.split("/");
        String[] dirs = new String[parts.length - 1];
        System.arraycopy(parts, 0, dirs, 0, dirs.length);
        DocumentFile dir = vault.dir(dirs);
        DocumentFile note = vault.find(dir, parts[parts.length - 1]);
        if (note == null) {
            // The owner moved or deleted the note: keep the transcript in a new note instead.
            note = vault.createNote(dir, parts[parts.length - 1], "", "%%none%%");
        }
        vault.append(note, block);
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        return InboxWorker.foregroundInfo(getApplicationContext());
    }
}
