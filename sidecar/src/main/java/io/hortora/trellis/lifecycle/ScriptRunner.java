package io.hortora.trellis.lifecycle;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
public class ScriptRunner {

    private static final Logger LOG = Logger.getLogger(ScriptRunner.class);
    private static final Pattern KEY_VALUE = Pattern.compile("^([A-Z_][A-Z0-9_]*)=(.*)$");

    @ConfigProperty(name = "trellis.skills.path", defaultValue = "${user.home}/.claude/skills")
    String skillsPath;

    void setSkillsPath(String path) {
        this.skillsPath = path;
    }

    public OperationResult run(String skillDir, String scriptName, List<String> args)
            throws IOException, InterruptedException {
        Path script = Path.of(skillsPath, skillDir, scriptName);
        if (!Files.isRegularFile(script)) {
            throw new IOException("Script not found: " + script);
        }

        var command = new ArrayList<String>();
        command.add("python3");
        command.add(script.toString());
        command.addAll(args);

        var pb = new ProcessBuilder(command);
        pb.directory(script.getParent().toFile());
        pb.redirectErrorStream(false);

        LOG.debugf("Running: %s %s", skillDir + "/" + scriptName, args);
        var process = pb.start();

        var stdout = new String(process.getInputStream().readAllBytes());
        var stderr = new String(process.getErrorStream().readAllBytes());
        int exitCode = process.waitFor();

        var output = parseKeyValues(stdout);

        if (exitCode != 0) {
            LOG.warnf("Script %s/%s exited with code %d: %s", skillDir, scriptName, exitCode, stderr);
        }

        return new OperationResult(exitCode == 0, exitCode, output, stderr);
    }

    private Map<String, String> parseKeyValues(String stdout) {
        var result = new LinkedHashMap<String, String>();
        for (String line : stdout.split("\n")) {
            var matcher = KEY_VALUE.matcher(line.trim());
            if (matcher.matches()) {
                result.put(matcher.group(1), matcher.group(2));
            }
        }
        return result;
    }
}
