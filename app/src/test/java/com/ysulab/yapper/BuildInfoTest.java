package com.ysulab.yapper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BuildInfoTest {

    @Test
    public void describesNormalBuild() {
        assertEquals("v0.0.42 (build 42, release)", BuildInfo.describe("0.0.42", 42, "release"));
    }

    @Test
    public void handlesMissingValues() {
        assertEquals("v? (build 1, unknown)", BuildInfo.describe(null, 1, ""));
    }
}
