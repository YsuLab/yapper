package com.ysulab.yapper.sort;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.junit.Test;

public class SafePathTest {

    @Test
    public void normalizesCommonForms() throws Exception {
        assertEquals("Uni/Algorithms.md", SafePath.normalize("Uni/Algorithms.md"));
        assertEquals("Uni/Algorithms.md", SafePath.normalize("/Uni//Algorithms"));
        assertEquals("Uni/Algorithms.md", SafePath.normalize("[[Uni/Algorithms]]"));
        assertEquals("Uni/Algorithms.md", SafePath.normalize("Uni\\Algorithms.MD"));
        assertEquals("Projects/Daftar.md", SafePath.normalize("./Projects/Daftar.md"));
        assertEquals("Uni/مقرر الخوارزميات.md", SafePath.normalize("Uni/مقرر الخوارزميات"));
        assertEquals("Tabby/Q A.md", SafePath.normalize("Tabby/Q: A?"));
    }

    @Test
    public void rejectsUnsafePaths() {
        for (String bad : new String[] {"../x.md", "Uni/../../x.md", ".obsidian/app.json", "_yapper/rules.md",
                "_Yapper/index.md", "", "   ", "a/b/c/d/e/f.md", "Uni/.md"}) {
            try {
                SafePath.normalize(bad);
                fail("accepted " + bad);
            } catch (JSONException expected) {
                // ok
            }
        }
    }
}
