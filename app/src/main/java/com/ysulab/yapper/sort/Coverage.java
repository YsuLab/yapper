package com.ysulab.yapper.sort;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Coverage check for split-then-sort: every sentence of the transcript must be part of an item's quote
 * or of a quote marked "no action". A sentence that maps to nothing is reported, so the caller turns it
 * into an Unsorted item. Nothing is dropped silently. Pure Java.
 */
public final class Coverage {

    /** A sentence counts as covered when this share of its words appear in one quote. */
    static final double MIN_SHARE = 0.6;
    /** Sentences shorter than this (in words) are filler ("okay", "يعني خلاص"). */
    static final int MIN_WORDS = 3;
    /** Transcripts often have no punctuation: long runs are checked in windows of this many words. */
    static final int WINDOW = 20;

    private Coverage() {}

    /** Sentences (or windows of a long run) that no quote covers, in transcript order. */
    public static List<String> uncovered(String transcript, Collection<String> quotes) {
        List<Set<String>> qs = new ArrayList<>();
        for (String q : quotes) {
            Set<String> t = new HashSet<>(words(q));
            if (!t.isEmpty()) {
                qs.add(t);
            }
        }
        List<String> out = new ArrayList<>();
        for (String s : sentences(transcript)) {
            List<String> w = words(s);
            if (w.size() < MIN_WORDS) {
                continue;
            }
            if (bestShare(w, qs) < MIN_SHARE) {
                out.add(s);
            }
        }
        return out;
    }

    /** True when {@code needle}'s words mostly appear in {@code hay} (used to match calls to items). */
    public static boolean mostlyIn(String needle, String hay) {
        List<String> w = words(needle);
        if (w.isEmpty()) {
            return false;
        }
        List<Set<String>> one = new ArrayList<>();
        one.add(new HashSet<>(words(hay)));
        return bestShare(w, one) >= MIN_SHARE;
    }

    static double bestShare(List<String> words, List<Set<String>> quotes) {
        double best = 0;
        for (Set<String> q : quotes) {
            int hit = 0;
            for (String w : words) {
                if (q.contains(w)) {
                    hit++;
                }
            }
            best = Math.max(best, hit / (double) words.size());
        }
        return best;
    }

    /** Splits on sentence ends (Latin and Arabic); runs longer than 1.5 windows are cut into windows. */
    static List<String> sentences(String transcript) {
        List<String> out = new ArrayList<>();
        if (transcript == null) {
            return out;
        }
        for (String part : transcript.split("[.!?؟…\\n;؛]+")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            String[] raw = s.split("\\s+");
            if (raw.length <= WINDOW * 3 / 2) {
                out.add(s);
                continue;
            }
            for (int i = 0; i < raw.length; i += WINDOW) {
                int end = Math.min(raw.length, i + WINDOW);
                if (raw.length - end < WINDOW / 2) {
                    end = raw.length; // fold a short tail into the last window
                }
                out.add(String.join(" ", java.util.Arrays.copyOfRange(raw, i, end)));
                if (end == raw.length) {
                    break;
                }
            }
        }
        return out;
    }

    /** Lower case, no Arabic diacritics or tatweel, letters and digits only, words of 2+ characters. */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\u064B-\\u0652\\u0670\\u0640]", "")
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي');
        for (String w : n.split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 2) {
                out.add(w);
            }
        }
        return out;
    }
}
