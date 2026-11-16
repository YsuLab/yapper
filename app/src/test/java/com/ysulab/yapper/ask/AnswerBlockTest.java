package com.ysulab.yapper.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.ysulab.yapper.sort.SortAction;
import com.ysulab.yapper.sort.Summary;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class AnswerBlockTest {

    @Test
    public void blockQuotesTheAnswerWithMarkerAndSources() {
        Asker.Answer a = new Asker.Answer();
        a.text = "You set up Cloud Run [[Google Cloud]].\nAlso [[Tabby]].";
        a.sources.addAll(Arrays.asList("Topics/Google Cloud.md", "Tabby/Tabby.md"));
        a.model = "m";
        a.latencyMs = 2500;
        a.steps.add("search for: midterm");
        String b = AnswerBlock.block("20261111-070000-abcd", 2, "what about\nGCP?", a);
        assertTrue(b, b.startsWith("\n## Answer · m · 2.5 s\n%%yap:20261111-070000-abcd/2%%\n"));
        assertTrue(b, b.contains("> [!question] what about GCP?\n> You set up Cloud Run [[Google Cloud]].\n> Also"));
        assertTrue(b, b.contains("Sources: [[Google Cloud]], [[Tabby]]"));
        assertTrue(b, b.contains("_Looked up: search for: midterm_"));
    }

    @Test
    public void plainTextForNotifications() {
        assertEquals("GCP on 2026-11-10 at Tabby",
                AnswerBlock.plain("[[Google Cloud|GCP]] on [[2026-11-10]] at [[Tabby]]"));
    }

    @Test
    public void askVaultParsesAndRoundTrips() throws Exception {
        SortAction a = SortAction.parse(SortAction.ASK_VAULT,
                "{\"question\":\"what did I say about GCP?\",\"terms\":[\"GCP\",\"Google Cloud\",\"GCP\"],"
                        + "\"since\":\"2026-11-01\",\"until\":\"next week\"}");
        assertEquals(Arrays.asList("GCP", "Google Cloud"), a.terms);
        assertEquals("2026-11-01", a.since);
        assertEquals("", a.until);
        SortAction b = SortAction.fromJsonArray(SortAction.toJsonArray(List.of(a))).get(0);
        assertEquals(a.question, b.question);
        assertEquals(a.terms, b.terms);
        assertEquals("2026-11-01", b.since);
    }

    @Test
    public void summaryKnowsQuestionOnlyCaptures() throws Exception {
        SortAction q = SortAction.parse(SortAction.ASK_VAULT, "{\"question\":\"q\",\"terms\":[]}");
        SortAction r = SortAction.parse(SortAction.REMINDER,
                "{\"title\":\"t\",\"remind_at\":\"2026-11-12T09:00:00+03:00\"}");
        assertTrue(Summary.onlyQuestions(List.of(q)));
        assertTrue(!Summary.onlyQuestions(List.of(r, q)));
        assertEquals(List.of(1), Summary.questionIndexes(List.of(r, q)));
        assertTrue(Summary.line(List.of(r, q)).contains("Answering 1 question…"));
    }
}
