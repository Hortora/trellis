package io.hortora.trellis.scanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static io.hortora.trellis.scanner.PathDomainClassifier.Domain.*;
import static org.junit.jupiter.api.Assertions.*;

class PathDomainClassifierTest {

    private final PathDomainClassifier classifier = new PathDomainClassifier();

    @TempDir Path root;

    @Test
    void gitHeadChangeClassifiesAsRepos() {
        var changed = root.resolve("my-repo/.git/HEAD");
        var result = classifier.classify(changed, root);
        assertEquals(java.util.Set.of(REPOS), result);
    }

    @Test
    void gitRefsChangeClassifiesAsRepos() {
        var changed = root.resolve("my-repo/.git/refs/heads/main");
        var result = classifier.classify(changed, root);
        assertEquals(java.util.Set.of(REPOS), result);
    }

    @Test
    void slotFileClassifiesAsSlots() {
        var changed = root.resolve("slots/1/.slot");
        var result = classifier.classify(changed, root);
        assertEquals(java.util.Set.of(SLOTS), result);
    }

    @Test
    void phaseCompleteClassifiesAsSlots() {
        var changed = root.resolve("slots/2/.phase-a-complete");
        var result = classifier.classify(changed, root);
        assertEquals(java.util.Set.of(SLOTS), result);
    }

    @Test
    void protocolIndexClassifiesAsProtocols() {
        var changed = root.resolve("my-repo/docs/protocols/INDEX.md");
        var result = classifier.classify(changed, root);
        assertEquals(java.util.Set.of(PROTOCOLS), result);
    }

    @Test
    void planFileClassifiesAsLifecycle() {
        var changed = root.resolve("slots/1/wsp-repo/design/.plan");
        var result = classifier.classify(changed, root);
        assertEquals(java.util.Set.of(LIFECYCLE), result);
    }

    @Test
    void pauseStackClassifiesAsLifecycle() {
        var changed = root.resolve("slots/1/wsp-repo/design/.pause-stack");
        var result = classifier.classify(changed, root);
        assertEquals(java.util.Set.of(LIFECYCLE), result);
    }

    @Test
    void epicFileClassifiesAsLifecycle() {
        var changed = root.resolve("slots/1/wsp-repo/design/.epic");
        var result = classifier.classify(changed, root);
        assertEquals(java.util.Set.of(LIFECYCLE), result);
    }

    @Test
    void worklogDbClassifiesAsWorklog() {
        var changed = root.resolve("worklog.db");
        var result = classifier.classify(changed, root);
        assertEquals(java.util.Set.of(WORKLOG), result);
    }

    @Test
    void unrelatedFileReturnsEmpty() {
        var changed = root.resolve("my-repo/src/Main.java");
        var result = classifier.classify(changed, root);
        assertTrue(result.isEmpty());
    }

    @Test
    void buildArtifactReturnsEmpty() {
        var changed = root.resolve("my-repo/target/classes/Foo.class");
        var result = classifier.classify(changed, root);
        assertTrue(result.isEmpty());
    }

    @Test
    void lifecycleFileInsideSlotMatchesLifecycle() {
        var changed = root.resolve("slots/1/.plan");
        var result = classifier.classify(changed, root);
        assertTrue(result.contains(LIFECYCLE));
    }
}
