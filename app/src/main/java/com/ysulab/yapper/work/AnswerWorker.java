package com.ysulab.yapper.work;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.ysulab.yapper.YapId;
import com.ysulab.yapper.ask.AnswerBlock;
import com.ysulab.yapper.ask.Asker;
import com.ysulab.yapper.ask.VaultSearch;
import com.ysulab.yapper.capture.CaptureMeta;
import com.ysulab.yapper.capture.CaptureStore;
import com.ysulab.yapper.settings.Settings;
import com.ysulab.yapper.sort.SortAction;
import com.ysulab.yapper.sort.SortException;
import com.ysulab.yapper.sort.SorterConfig;
import com.ysulab.yapper.sort.Summary;
import com.ysulab.yapper.vault.EntityIndex;
import com.ysulab.yapper.vault.SafVaultFs;
import com.ysulab.yapper.vault.VaultAccess;
import com.ysulab.yapper.vault.VaultIndex;
import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.json.JSONObject;

/**
 * Ask your vault: answers each {@code ask_vault} action of a sorted capture. Searches the vault on
 * the phone, asks the model (same provider as the sorter), appends the answer to the capture note
 * and shows it in a notification. Each answer is saved in the bundle first ({@code answer-N.txt}),
 * and the capture note is checked for the block's {@code %%yap%%} marker, so a retry never asks
 * twice or appends twice. The owner's notes are never written.
 */
public final class AnswerWorker extends Worker {

    static final int MAX_ATTEMPTS = 8;

    public AnswerWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    static String answerFile(int index) {
        return "answer-" + index + ".txt";
    }

    /** True when every question in the plan has an answer (or a recorded failure). */
    static boolean allAnswered(CaptureStore store, String id, List<SortAction> plan) {
        for (int i : Summary.questionIndexes(plan)) {
            if (!store.file(id, answerFile(i)).isFile()) {
                return false;
            }
        }
        return true;
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
        List<SortAction> plan;
        try {
            m = store.load(id);
            String planJson = store.readText(id, SortWorker.PLAN);
            if (planJson.isEmpty()) {
                return Result.success();
            }
            plan = SortAction.fromJsonArray(planJson);
        } catch (Exception e) {
            return Result.failure();
        }
        List<Integer> questions = Summary.questionIndexes(plan);
        if (questions.isEmpty()) {
            return Result.success();
        }
        SorterConfig cfg = settings.sorter();
        if (!cfg.isConfigured()) {
            return Result.success();
        }
        VaultAccess vault = VaultAccess.open(ctx, settings.vaultUri());
        if (vault == null) {
            return Result.retry();
        }
        SafVaultFs fs = new SafVaultFs(vault);
        ZoneId zone = SortWorker.zone(m);
        // Capture time, not now: "yesterday" means the day before he asked, even if the answer was queued.
        ZonedDateTime now = java.time.Instant.ofEpochMilli(m.createdAtMs).atZone(zone);
        try {
            VaultSearch search = new VaultSearch(fs.allNotes(new File(ctx.getFilesDir(), "search-cache.json")),
                    EntityIndex.fromIndex(fs.read(VaultIndex.INDEX_PATH)), zone);
            String template = SortWorker.asset(ctx, "ask/system.md");
            for (int i : questions) {
                SortAction q = plan.get(i);
                String saved = store.readText(id, answerFile(i));
                if (saved.isEmpty() && store.file(id, answerFile(i)).isFile()) {
                    continue; // failed for good earlier; the capture note says so
                }
                JSONObject ans;
                if (!saved.isEmpty()) {
                    ans = new JSONObject(saved); // asked before a crash: reuse, never ask twice
                } else {
                    Asker.Answer a;
                    try {
                        a = new Asker(cfg, template, search, now, Asker.http(cfg)).ask(q.question, query(q));
                    } catch (SortException e) {
                        if (e.retryable && getRunAttemptCount() < MAX_ATTEMPTS) {
                            return Result.retry();
                        }
                        appendOnce(fs, store.load(id), i, AnswerBlock.failed(id, i, q.question, e.getMessage()));
                        store.writeText(id, answerFile(i), "");
                        Notifications.status(ctx, "Couldn't answer", e.getMessage());
                        continue;
                    }
                    // Bundle first, then the note, then the notification.
                    ans = new JSONObject()
                            .put("block", AnswerBlock.block(id, i, q.question, a))
                            .put("text", AnswerBlock.plain(a.text))
                            .put("source", a.sources.isEmpty() ? "" : a.sources.get(0));
                    store.writeText(id, answerFile(i), ans.toString());
                }
                m = store.load(id);
                if (appendOnce(fs, m, i, ans.getString("block"))) {
                    String src = ans.optString("source", "");
                    Notifications.answer(ctx, id, i, q.question, ans.optString("text"),
                            src.isEmpty() ? m.inboxPath : src, m.inboxPath, vault.name());
                }
            }
            return Result.success();
        } catch (Exception e) {
            // IOException from the vault, or JSONException from our own answer file.
            return getRunAttemptCount() < MAX_ATTEMPTS ? Result.retry() : Result.success();
        }
    }

    /** Appends to the capture note unless this question's marker is already there. True if appended. */
    static boolean appendOnce(SafVaultFs fs, CaptureMeta m, int index, String block) throws IOException {
        if (m.inboxPath.isEmpty()) {
            return true; // no note to write to; still show the answer
        }
        String current = fs.read(m.inboxPath);
        if (current == null) {
            return true;
        }
        if (current.contains(YapId.blockMarker(m.id, index))) {
            return false;
        }
        fs.append(m.inboxPath, block);
        return true;
    }

    /** The sorter's terms and dates; terms from the question itself when the sorter gave none and no dates. */
    static VaultSearch.Query query(SortAction q) {
        org.json.JSONArray terms = new org.json.JSONArray(q.terms);
        VaultSearch.Query query = Asker.query(terms, q.since, q.until);
        if (query.terms.isEmpty() && query.since == null && query.until == null) {
            query.terms.addAll(VaultSearch.termsFromQuestion(q.question));
        }
        return query;
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        return InboxWorker.foregroundInfo(getApplicationContext());
    }
}
