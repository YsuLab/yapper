package com.ysulab.yapper.work;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.ysulab.yapper.capture.CaptureMeta;
import com.ysulab.yapper.capture.CaptureStore;
import com.ysulab.yapper.prayer.PrayerTimes;
import com.ysulab.yapper.capture.SharedItems;
import com.ysulab.yapper.settings.Settings;
import com.ysulab.yapper.sort.ActionLog;
import com.ysulab.yapper.sort.Executor;
import com.ysulab.yapper.sort.SortAction;
import com.ysulab.yapper.sort.SortException;
import com.ysulab.yapper.sort.Sorter;
import com.ysulab.yapper.sort.SorterConfig;
import com.ysulab.yapper.sort.SorterInput;
import com.ysulab.yapper.sort.SorterPrompt;
import com.ysulab.yapper.sort.Summary;
import com.ysulab.yapper.transcribe.Transcriber;
import com.ysulab.yapper.vault.InboxNote;
import com.ysulab.yapper.vault.SafVaultFs;
import com.ysulab.yapper.vault.VaultAccess;
import com.ysulab.yapper.vault.VaultIndex;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * M2: transcript → sorter (LLM tool calls) → vault writes → result notification with Undo/View.
 *
 * <p>The plan is saved in the bundle ({@code plan.json}) before anything is written, and every
 * write goes into {@code actions.json}. A retry re-uses the same plan, and the executor skips blocks
 * that are already in the vault, so a crash never duplicates or loses anything.
 */
public final class SortWorker extends Worker {

    static final String PLAN = "plan.json";
    static final String ACTIONS = "actions.json";
    static final String SORT_INFO = "sort.properties";
    static final String TRANSCRIPT_PREFIX = "transcript-";
    static final int MAX_ATTEMPTS = 12;
    /** Today plus a week, so "next Friday after Jumuah" still resolves. */
    static final int PRAYER_DAYS = 8;
    static final String CAPTURES_DIR = VaultIndex.APP_DIR + "/captures";

    public SortWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Bundle file name holding one transcriber's transcript. */
    static String transcriptFile(String transcriberId) {
        return TRANSCRIPT_PREFIX + transcriberId.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt";
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
        if (m.state != CaptureMeta.State.DONE || !(m.sorted.isEmpty() || "planned".equals(m.sorted))) {
            return Result.success();
        }
        SorterConfig cfg = settings.sorter();
        if (!cfg.isConfigured()) {
            return Result.success(); // reconcile() re-queues once a key is added
        }
        VaultAccess vault = VaultAccess.open(ctx, settings.vaultUri());
        if (vault == null) {
            return Result.retry();
        }
        SafVaultFs fs = new SafVaultFs(vault);
        ZoneId zone = zone(m);
        ZonedDateTime captured = Instant.ofEpochMilli(m.createdAtMs).atZone(zone);

        try {
            ensureRules(ctx, fs);
            List<SortAction> plan;
            String model;
            long latency;
            List<String> dropped = new ArrayList<>();
            String planJson = store.readText(id, PLAN);
            if (!planJson.isEmpty()) {
                plan = SortAction.fromJsonArray(planJson);
                java.util.Properties info = new java.util.Properties();
                info.load(new java.io.StringReader(store.readText(id, SORT_INFO)));
                model = info.getProperty("model", cfg.modelId());
                latency = Long.parseLong(info.getProperty("latencyMs", "0"));
                String d = info.getProperty("dropped", "");
                if (!d.isEmpty()) {
                    for (String line : d.split("\n")) {
                        dropped.add(line);
                    }
                }
            } else {
                SorterInput in = input(ctx, store, settings, cfg, fs, m, captured);
                if (in == null) {
                    store.update(id, x -> x.sorted = "skipped");
                    return Result.success();
                }
                Sorter.Result r;
                try {
                    r = new Sorter(cfg, asset(ctx, "sorter/system.md"), asset(ctx, "sorter/tools.json")).sort(in);
                } catch (SortException e) {
                    if (e.retryable && getRunAttemptCount() < MAX_ATTEMPTS) {
                        return Result.retry();
                    }
                    fail(ctx, store, fs, m, e.getMessage());
                    return Result.success();
                }
                plan = r.actions;
                model = r.model.isEmpty() ? cfg.modelId() : r.model;
                latency = r.latencyMs;
                dropped.addAll(r.dropped);
                java.util.Properties info = new java.util.Properties();
                info.setProperty("model", model);
                info.setProperty("latencyMs", Long.toString(latency));
                info.setProperty("dropped", String.join("\n", dropped));
                info.setProperty("shadow", Boolean.toString(cfg.shadow));
                java.io.StringWriter w = new java.io.StringWriter();
                info.store(w, "sorter run");
                store.writeText(id, SORT_INFO, w.toString());
                store.writeText(id, PLAN, SortAction.toJsonArray(plan));
                m = store.update(id, x -> x.sorted = "planned");
            }

            String summary = Summary.line(plan);
            if (cfg.shadow) {
                appendToCapture(fs, m, Summary.proposalBlock(id, plan, model, latency, zone, dropped));
                store.update(id, x -> {
                    x.sorted = "shadow";
                    x.sortSummary = summary;
                });
                if (!Summary.questionIndexes(plan).isEmpty()) {
                    // Answers only go into the capture note, so they run in shadow mode too.
                    CaptureScheduler.enqueueAnswer(ctx, id);
                }
                Notifications.sortResult(ctx, id, "Preview (shadow mode)", summary, m.inboxPath, vault.name(),
                        false);
                return Result.success();
            }

            ActionLog log = ActionLog.parse(store.readText(id, ACTIONS));
            Executor ex = new Executor(fs, id, noteName(m.inboxPath), captured)
                    .withEntities(com.ysulab.yapper.vault.EntityIndex.fromIndex(fs.read(VaultIndex.INDEX_PATH)));
            boolean pushTodo;
            try {
                ex.execute(plan, log);
            } finally {
                store.writeText(id, ACTIONS, log.serialize());
            }
            // M3: local alarms are set now (offline); To Do tasks go out in ReminderWorker.
            com.ysulab.yapper.reminders.MsAccount acct = new com.ysulab.yapper.reminders.MsAccount(ctx);
            pushTodo = new com.ysulab.yapper.reminders.ReminderSync(id,
                    new com.ysulab.yapper.reminders.LocalAlarms(ctx), settings.alarmWindowMs())
                    .route(log, System.currentTimeMillis(), acct.isReady());
            store.writeText(id, ACTIONS, log.serialize());
            appendToCapture(fs, m, Summary.sortedBlock(id, plan, model, latency, zone, dropped));
            if (cfg.moveSorted) {
                m = moveCapture(fs, store, m, CAPTURES_DIR);
            }
            store.update(id, x -> {
                x.sorted = "done";
                x.sortSummary = summary;
            });
            if (pushTodo) {
                CaptureScheduler.enqueueReminders(ctx, id);
            }
            List<Integer> questions = Summary.questionIndexes(plan);
            if (!questions.isEmpty()) {
                CaptureScheduler.enqueueAnswer(ctx, id);
            }
            if (Summary.onlyQuestions(plan)) {
                // Just a question: a quiet "Looking it up…" that the answer replaces.
                Notifications.lookingUp(ctx, id, plan.get(0).question);
                return Result.success();
            }
            String view = Summary.viewPath(plan);
            Notifications.sortResult(ctx, id, "Sorted ✓", summary, view == null ? m.inboxPath : view,
                    vault.name(), true);
            return Result.success();
        } catch (Exception e) {
            // IOException from the vault, or a JSON/Properties problem in our own files.
            return getRunAttemptCount() < MAX_ATTEMPTS ? Result.retry() : failSafely(ctx, store, fs, m, e);
        }
    }

    private Result failSafely(Context ctx, CaptureStore store, SafVaultFs fs, CaptureMeta m, Exception e) {
        try {
            fail(ctx, store, fs, m, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (IOException ignored) {
            // nothing more we can do; the bundle and Inbox note are still safe
        }
        return Result.success();
    }

    private static void fail(Context ctx, CaptureStore store, SafVaultFs fs, CaptureMeta m, String reason)
            throws IOException {
        try {
            appendToCapture(fs, m, Summary.failedBlock(m.id, reason));
        } catch (IOException ignored) {
            // still mark it failed
        }
        store.update(m.id, x -> {
            x.sorted = "failed";
            x.sortSummary = reason == null ? "" : reason;
        });
        Notifications.status(ctx, "Sorting failed", (reason == null ? "" : reason)
                + " · The capture is safe in Inbox.");
    }

    /** Builds the sorter input, or null when there is nothing to sort (no transcript, no screen text). */
    private static SorterInput input(Context ctx, CaptureStore store, Settings settings, SorterConfig cfg,
            SafVaultFs fs, CaptureMeta m, ZonedDateTime captured) throws IOException {
        SorterInput in = new SorterInput();
        in.transcript = transcript(store, settings, m.id);
        in.screenText = store.readText(m.id, CaptureStore.SCREEN_TEXT);
        if (in.transcript.trim().isEmpty() && !m.hasShared()) {
            return null; // screen-only captures stay in Inbox
        }
        if ("share".equals(m.mode)) {
            addShared(store, cfg, m, in);
        }
        in.now = captured;
        in.mode = m.mode;
        in.prayers = PrayerTimes.table(
                captured.withZoneSameInstant(PrayerTimes.SAUDI).toLocalDate(), PRAYER_DAYS);
        in.sourceApp = m.sourceApp;
        in.sourceUrl = m.webUri;
        String rules = fs.read(VaultIndex.RULES_PATH);
        in.rules = rules == null ? "" : rules;
        in.index = fs.buildIndex(new File(ctx.getFilesDir(), "index-cache.json"));
        if ("review".equals(m.mode)) {
            // Weekly review: the auto-built week summary is what he reflects on.
            com.ysulab.yapper.review.WeekRange week =
                    com.ysulab.yapper.review.WeekRange.forReview(captured.toLocalDate());
            in.weeklyPath = week.notePath();
            in.weekly = com.ysulab.yapper.review.WeekDigest.prepare(fs, week, captured.getZone(),
                    WeeklyWorker.stateDir(ctx)).markdown;
        }
        String date = captured.toLocalDate().toString();
        in.dailyPath = Executor.dailyPath(date);
        String daily = fs.read(in.dailyPath);
        in.daily = daily == null ? "" : daily;
        if (cfg.screenshots && "saved".equals(m.screenshot)
                && in.screenText.trim().length() < SorterPrompt.THIN_SCREEN_TEXT) {
            in.screenshotBase64 = screenshot(store.file(m.id, CaptureStore.SCREENSHOT));
        }
        return in;
    }

    /** Shared text, file names, up to 4 images (downscaled) and one PDF (OpenRouter only). */
    private static void addShared(CaptureStore store, SorterConfig cfg, CaptureMeta m, SorterInput in)
            throws IOException {
        in.sharedText = store.readText(m.id, SharedItems.TEXT_FILE);
        boolean pdfOk = cfg.url().toLowerCase(java.util.Locale.ROOT).startsWith(SorterConfig.DEFAULT_URL);
        for (String f : m.sharedFiles) {
            String vaultName = SharedItems.vaultName(m.id, f);
            in.sharedFiles.add(vaultName);
            File file = store.file(m.id, f);
            if (SharedItems.isImage(f) && in.sharedImagesBase64.size() < SharedItems.MAX_IMAGES_TO_SORTER) {
                // Timetables need legible small print: allow more pixels than a screenshot gets.
                String b64 = jpegBase64(file, 1600);
                if (b64 != null) {
                    in.sharedImagesBase64.add(b64);
                }
            } else if (pdfOk && SharedItems.isPdf(f) && in.sharedPdfBase64 == null && file.isFile()
                    && file.length() <= SharedItems.MAX_PDF_TO_SORTER_BYTES) {
                in.sharedPdfBase64 = Base64.encodeToString(
                        java.nio.file.Files.readAllBytes(file.toPath()), Base64.NO_WRAP);
                in.sharedPdfName = vaultName;
            }
        }
    }

    /** The transcript of the highest-priority transcriber that produced one. */
    static String transcript(CaptureStore store, Settings settings, String id) throws IOException {
        for (Transcriber t : settings.transcribers()) {
            String text = store.readText(id, transcriptFile(t.id()));
            if (!text.trim().isEmpty()) {
                return text;
            }
        }
        File[] files = store.dir(id).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith(TRANSCRIPT_PREFIX)) {
                    String text = store.readText(id, f.getName());
                    if (!text.trim().isEmpty()) {
                        return text;
                    }
                }
            }
        }
        return "";
    }

    static boolean hasTranscript(CaptureStore store, String id) {
        File[] files = store.dir(id).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith(TRANSCRIPT_PREFIX) && f.length() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String screenshot(File f) {
        return jpegBase64(f, 720);
    }

    /** Decodes, downsamples to at most about {@code maxWidth} px wide, and re-encodes as JPEG base64. */
    private static String jpegBase64(File f, int maxWidth) {
        if (!f.isFile()) {
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxWidth) {
            sample *= 2;
        }
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) {
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 75, bos);
        b.recycle();
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    /** Creates the starter {@code _yapper/rules.md} if the owner has none yet. Never overwrites. */
    static void ensureRules(Context ctx, SafVaultFs fs) throws IOException {
        if (fs.read(VaultIndex.RULES_PATH) == null) {
            fs.create(VaultIndex.RULES_PATH, asset(ctx, "sorter/starter-rules.md"));
        }
    }

    /** Appends to the capture note, once: a retry sees the block's heading line and skips it. */
    static void appendToCapture(SafVaultFs fs, CaptureMeta m, String block) throws IOException {
        if (m.inboxPath.isEmpty()) {
            return;
        }
        String current = fs.read(m.inboxPath);
        String headingLine = block.trim().split("\n")[0];
        if (current != null && !current.contains("\n" + headingLine + "\n")) {
            fs.append(m.inboxPath, block);
        }
    }

    /**
     * Moves the capture note and its attachments to {@code toDir} (and {@code toDir/attachments}).
     * Obsidian resolves {@code ![[name]]} embeds by file name, so links keep working.
     */
    static CaptureMeta moveCapture(SafVaultFs fs, CaptureStore store, CaptureMeta m, String toDir)
            throws IOException {
        if (m.inboxPath.isEmpty() || m.inboxPath.startsWith(toDir + "/")) {
            return m;
        }
        String fromDir = m.inboxPath.contains("/") ? m.inboxPath.substring(0, m.inboxPath.lastIndexOf('/')) : "";
        String prefix = fromDir.isEmpty() ? "" : fromDir + "/";
        List<String> atts = new ArrayList<>(InboxNote.audioNames(m));
        atts.add(InboxNote.screenshotName(m));
        atts.addAll(InboxNote.sharedNames(m));
        for (String att : atts) {
            String from = prefix + InboxNote.ATTACHMENTS_DIR + "/" + att;
            String to = toDir + "/" + InboxNote.ATTACHMENTS_DIR + "/" + att;
            if (!fs.exists(to)) {
                fs.move(from, to);
            }
        }
        String to = toDir + "/" + noteName(m.inboxPath) + ".md";
        if (!fs.exists(to)) {
            fs.move(m.inboxPath, to);
        }
        return store.update(m.id, x -> x.inboxPath = to);
    }

    static String noteName(String path) {
        String n = path.substring(path.lastIndexOf('/') + 1);
        return n.endsWith(".md") ? n.substring(0, n.length() - 3) : n;
    }

    static ZoneId zone(CaptureMeta m) {
        try {
            return ZoneId.of(m.zoneId == null || m.zoneId.isEmpty() ? Settings.ZONE : m.zoneId);
        } catch (RuntimeException e) {
            return ZoneId.of(Settings.ZONE);
        }
    }

    static String asset(Context ctx, String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        return InboxWorker.foregroundInfo(getApplicationContext());
    }
}
