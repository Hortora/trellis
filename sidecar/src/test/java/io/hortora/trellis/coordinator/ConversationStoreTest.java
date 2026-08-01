package io.hortora.trellis.coordinator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class ConversationStoreTest {

    @TempDir Path tmpDir;
    private DataSource ds;
    private ConversationStore store;

    @BeforeEach
    void setUp() throws SQLException {
        var sqlDs = new SQLiteDataSource();
        sqlDs.setUrl("jdbc:sqlite:" + tmpDir.resolve("test.db"));
        ds = sqlDs;
        new CoordinatorSchemaManager().initialize(ds);
        store = ConversationStore.forTest(ds);
    }

    @Test
    void appendAndRetrieve() {
        store.append("ws1", ConversationTurn.Role.USER, "hello");
        store.append("ws1", ConversationTurn.Role.COORDINATOR, "hi there");

        var turns = store.history("ws1", 50);
        assertEquals(2, turns.size());
        assertEquals(ConversationTurn.Role.USER, turns.get(0).role());
        assertEquals("hello", turns.get(0).content());
        assertEquals(ConversationTurn.Role.COORDINATOR, turns.get(1).role());
        assertEquals("hi there", turns.get(1).content());
    }

    @Test
    void workspaceIsolation() {
        store.append("ws1", ConversationTurn.Role.USER, "for ws1");
        store.append("ws2", ConversationTurn.Role.USER, "for ws2");

        assertEquals(1, store.history("ws1", 50).size());
        assertEquals(1, store.history("ws2", 50).size());
    }

    @Test
    void maxTurnsLimitsResult() {
        for (int i = 0; i < 10; i++) {
            store.append("ws1", ConversationTurn.Role.USER, "msg " + i);
        }

        var turns = store.history("ws1", 3);
        assertEquals(3, turns.size());
        assertEquals("msg 7", turns.get(0).content());
        assertEquals("msg 8", turns.get(1).content());
        assertEquals("msg 9", turns.get(2).content());
    }

    @Test
    void emptyHistoryReturnsEmptyList() {
        assertTrue(store.history("nonexistent", 50).isEmpty());
    }

    @Test
    void turnsHaveIncreasingIds() {
        store.append("ws1", ConversationTurn.Role.USER, "a");
        store.append("ws1", ConversationTurn.Role.COORDINATOR, "b");
        var turns = store.history("ws1", 50);
        assertTrue(turns.get(0).id() < turns.get(1).id());
    }
}
