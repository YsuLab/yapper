package com.ysulab.yapper.ask;

import com.ysulab.yapper.YapId;
import com.ysulab.yapper.vault.EntityIndex;
import java.util.Locale;

/** Text for an answer: the block appended to the capture note, and the notification text. Pure Java. */
public final class AnswerBlock {

    private AnswerBlock() {}

    /**
     * Appended to the capture note (never to the owner's notes). The marker carries the action
     * index, so a retry sees it and does not append twice.
     */
    public static String block(String captureId, int index, String question, Asker.Answer a) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## Answer · ").append(a.model.isEmpty() ? "model" : a.model)
                .append(String.format(Locale.ROOT, " · %.1f s", a.latencyMs / 1000.0)).append('\n');
        sb.append(YapId.blockMarker(captureId, index)).append('\n');
        sb.append("> [!question] ").append(oneLine(question)).append('\n');
        for (String line : a.text.trim().split("\n")) {
            sb.append("> ").append(line).append('\n');
        }
        if (!a.sources.isEmpty()) {
            sb.append("\nSources: ");
            for (int i = 0; i < a.sources.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append("[[").append(EntityIndex.baseName(a.sources.get(i))).append("]]");
            }
            sb.append('\n');
        }
        if (!a.steps.isEmpty()) {
            sb.append("\n_Looked up: ").append(oneLine(String.join(" · ", a.steps))).append("_\n");
        }
        return sb.toString();
    }

    /** A failed question, so the capture note says what happened. */
    public static String failed(String captureId, int index, String question, String reason) {
        return "\n## Answer failed\n" + YapId.blockMarker(captureId, index) + "\n> [!question] "
                + oneLine(question) + "\n" + oneLine(reason == null ? "" : reason) + "\n";
    }

    /** Notification text: links shown as plain names ([[Google Cloud|GCP]] → GCP). */
    public static String plain(String text) {
        return (text == null ? "" : text)
                .replaceAll("\\[\\[[^\\]|]*\\|([^\\]]*)\\]\\]", "$1")
                .replaceAll("\\[\\[([^\\]]*)\\]\\]", "$1")
                .trim();
    }

    static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
