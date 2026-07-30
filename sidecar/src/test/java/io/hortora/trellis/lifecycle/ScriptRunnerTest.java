package io.hortora.trellis.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScriptRunnerTest {

    @TempDir
    Path tempDir;

    ScriptRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ScriptRunner();
        runner.setSkillsPath(tempDir.toString());
    }

    @Test
    void parsesKeyValueOutput() throws Exception {
        writeScript("test-skill", "test_script.py", """
                #!/usr/bin/env python3
                print("ROUTE=start")
                print("BRANCH=issue-42-feature")
                print("STATUS=ok")
                """);

        var result = runner.run("test-skill", "test_script.py", List.of());

        assertTrue(result.success());
        assertEquals("start", result.output().get("ROUTE"));
        assertEquals("issue-42-feature", result.output().get("BRANCH"));
        assertEquals("ok", result.output().get("STATUS"));
    }

    @Test
    void nonZeroExitReturnsFailure() throws Exception {
        writeScript("test-skill", "fail_script.py", """
                #!/usr/bin/env python3
                import sys
                print("ERROR=something_broke")
                sys.exit(1)
                """);

        var result = runner.run("test-skill", "fail_script.py", List.of());

        assertFalse(result.success());
        assertEquals("something_broke", result.output().get("ERROR"));
    }

    @Test
    void passesArguments() throws Exception {
        writeScript("test-skill", "args_script.py", """
                #!/usr/bin/env python3
                import sys
                for i, arg in enumerate(sys.argv[1:]):
                    print(f"ARG{i}={arg}")
                """);

        var result = runner.run("test-skill", "args_script.py", List.of("create-branches", "/tmp/repo"));

        assertTrue(result.success());
        assertEquals("create-branches", result.output().get("ARG0"));
        assertEquals("/tmp/repo", result.output().get("ARG1"));
    }

    @Test
    void ignoresNonKeyValueLines() throws Exception {
        writeScript("test-skill", "mixed_script.py", """
                #!/usr/bin/env python3
                print("some log message")
                print("KEY=value")
                print("another log line without equals")
                """);

        var result = runner.run("test-skill", "mixed_script.py", List.of());

        assertTrue(result.success());
        assertEquals(1, result.output().size());
        assertEquals("value", result.output().get("KEY"));
    }

    @Test
    void handlesEmptyOutput() throws Exception {
        writeScript("test-skill", "empty_script.py", """
                #!/usr/bin/env python3
                pass
                """);

        var result = runner.run("test-skill", "empty_script.py", List.of());

        assertTrue(result.success());
        assertTrue(result.output().isEmpty());
    }

    @Test
    void capturesStderr() throws Exception {
        writeScript("test-skill", "stderr_script.py", """
                #!/usr/bin/env python3
                import sys
                print("KEY=value")
                print("warning message", file=sys.stderr)
                """);

        var result = runner.run("test-skill", "stderr_script.py", List.of());

        assertTrue(result.success());
        assertTrue(result.stderr().contains("warning"));
    }

    @Test
    void throwsForMissingScript() {
        assertThrows(IOException.class, () ->
                runner.run("nonexistent-skill", "missing.py", List.of()));
    }

    private void writeScript(String skillDir, String scriptName, String content) throws IOException {
        Path dir = tempDir.resolve(skillDir);
        Files.createDirectories(dir);
        Path script = dir.resolve(scriptName);
        Files.writeString(script, content);
        script.toFile().setExecutable(true);
    }
}
