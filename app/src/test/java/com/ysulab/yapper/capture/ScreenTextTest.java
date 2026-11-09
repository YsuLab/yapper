package com.ysulab.yapper.capture;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ScreenTextTest {

    static final class N implements ScreenText.Node {
        final String text;
        final String desc;
        final boolean visible;
        final List<N> kids;

        N(String text, String desc, boolean visible, N... kids) {
            this.text = text;
            this.desc = desc;
            this.visible = visible;
            this.kids = new ArrayList<>(Arrays.asList(kids));
        }

        static N t(String text, N... kids) {
            return new N(text, null, true, kids);
        }

        @Override public CharSequence text() { return text; }
        @Override public CharSequence contentDescription() { return desc; }
        @Override public boolean visible() { return visible; }
        @Override public int childCount() { return kids.size(); }
        @Override public ScreenText.Node child(int i) { return kids.get(i); }
    }

    @Test
    public void walksInTreeOrder() {
        N root = N.t(null, N.t("Exam timetable"), N.t(null, N.t("Algorithms"), N.t("Sun 10:00")), N.t("OS"));
        assertEquals("Exam timetable\nAlgorithms\nSun 10:00\nOS",
                ScreenText.collect(Collections.singletonList(root), 1000));
    }

    @Test
    public void keepsRepeatedTimesButCollapsesImmediateDuplicates() {
        N root = N.t(null, N.t("10:00"), N.t("Algorithms"), N.t("10:00"), N.t("OS"), N.t("OS"));
        assertEquals("10:00\nAlgorithms\n10:00\nOS", ScreenText.collect(Collections.singletonList(root), 1000));
    }

    @Test
    public void skipsInvisibleSubtrees() {
        N hidden = new N("secret draft", null, false, N.t("child of hidden"));
        N root = N.t(null, N.t("shown"), hidden);
        assertEquals("shown", ScreenText.collect(Collections.singletonList(root), 1000));
    }

    @Test
    public void dropsDescriptionEqualToText() {
        N root = N.t(null, new N("Send", "Send", true), new N(null, "Voice message 0:12", true));
        assertEquals("Send\nVoice message 0:12", ScreenText.collect(Collections.singletonList(root), 1000));
    }

    @Test
    public void arabicAndWhitespaceCleanup() {
        N root = N.t(null, N.t("  اختبار   الخوارزميات   الأحد  "), N.t("line1 \n\n  line2"));
        assertEquals("اختبار الخوارزميات الأحد\nline1\nline2",
                ScreenText.collect(Collections.singletonList(root), 1000));
    }

    @Test
    public void respectsMaxChars() {
        N root = N.t(null, N.t("aaaa"), N.t("bbbb"), N.t("cccc"));
        assertEquals("aaaa\nbbbb", ScreenText.collect(Collections.singletonList(root), 11));
    }

    @Test
    public void handlesNullRootsAndChildren() {
        List<ScreenText.Node> roots = new ArrayList<>();
        roots.add(null);
        roots.add(N.t("ok"));
        assertEquals("ok", ScreenText.collect(roots, 100));
    }
}
