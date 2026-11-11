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
        List<File> audio = new java.util.ArrayList<>();
        for (String part : m.parts()) {
            File f = store.file(id, part);
            if (f.isFile() && f.length() > 0) {
                audio.add(f);
            }
        }
        List<Transcriber> active = settings.transcribers();
        if (audio.isEmpty() && m.hasShared()) {
            // A share without a voice note: nothing to transcribe, go straight to the sorter.
            try {
                store.update(id, x -> x.advanceTo(CaptureMeta.State.DONE));
            } catch (IOException e) {
                return Result.retry();
            }
            if (settings.sorter().isConfigured()) {
                CaptureScheduler.enqueueSort(ctx, id);
            }
            return Result.success();
        }
        if (audio.isEmpty() || active.isEmpty()) {
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
                // M4: a ramble has several parts. Each part's text is saved as soon as it arrives,
                // so a retry only redoes the parts that are missing.
                StringBuilder all = new StringBuilder();
                long latency = 0;
                boolean partsPending = false;
                for (int i = 0; i < audio.size(); i++) {
                    String partFile = partFile(t.id(), i);
                    if (!store.file(id, partFile).isFile()) {
                        try {
                            Transcriber.Transcript tr = t.transcribe(audio.get(i));
                            store.writeText(id, partFile, tr.text == null ? "" : tr.text.trim());
                            latency += tr.latencyMs;
                        } catch (TranscriptionException e) {
                            if (e.retryable && getRunAttemptCount() < MAX_ATTEMPTS) {
                                partsPending = true;
                                continue;
                            }
                            if (audio.size() == 1) {
                                throw e;
                            }
                            // One bad part must not sink a 40-minute ramble: note the gap, keep the rest.
                            store.writeText(id, partFile, "[part " + (i + 1) + " could not be transcribed: "
                                    + e.getMessage() + "]");
                        }
                    }
                    String text = store.readText(id, partFile);
                    if (!text.isEmpty()) {
                        all.append(all.length() == 0 ? "" : "\n\n").append(text);
                    }
                }
                if (partsPending) {
                    needRetry = true;
                    continue;
                }
                String text = all.toString();
                block = InboxNote.transcriptBlock(id, t.label(), latency, text);
                lastText = text;
                // Kept in the bundle for the sorter (M2), before the note, so a retry has it too.
                store.writeText(id, SortWorker.transcriptFile(t.id()), text);
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

    /** Bundle file with one transcriber's text for audio part {@code index} (0-based). */
    static String partFile(String transcriberId, int index) {
        return String.format(java.util.Locale.ROOT, "part-%02d-", index + 1)
                + SortWorker.transcriptFile(transcriberId);
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
