package io.hortora.trellis.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PathUtilTest {

    @Test
    void expandTildeReplacesLeadingTildeWithHomeDir() {
        String home = System.getProperty("user.home");
        assertEquals(home + "/claude/casehub", PathUtil.expandTilde("~/claude/casehub"));
    }

    @Test
    void expandTildePreservesAbsolutePath() {
        assertEquals("/Users/dev/project", PathUtil.expandTilde("/Users/dev/project"));
    }

    @Test
    void expandTildePreservesTildeInMiddle() {
        assertEquals("/some/path/~file", PathUtil.expandTilde("/some/path/~file"));
    }

    @Test
    void expandTildeHandlesNull() {
        assertNull(PathUtil.expandTilde(null));
    }

    @Test
    void expandTildeHandlesTildeAlone() {
        String home = System.getProperty("user.home");
        assertEquals(home, PathUtil.expandTilde("~"));
    }
}
