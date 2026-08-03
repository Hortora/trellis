package io.hortora.trellis.coordinator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionResponseParserTest {

    @Test
    void parsesAdviceWithNestedAction() {
        var json = """
                {"type": "SUGGESTION", "title": "Merge slot", "body": "Ready to merge",
                 "actionKey": "a1",
                 "action": {
                   "category": "LIFECYCLE",
                   "actionType": "slot.merge",
                   "params": {"slotId": "s1", "workspaceRoot": "/ws"},
                   "rationale": "All tests passing"
                 }}""";
        var result = ActionResponseParser.parseAction(json);
        assertTrue(result.isPresent());
        var action = result.get();
        assertEquals(ActionCategory.LIFECYCLE, action.category());
        assertEquals("slot.merge", action.actionType());
        assertEquals("s1", action.params().get("slotId"));
        assertEquals("All tests passing", action.rationale());
    }

    @Test
    void returnsEmptyForAdviceWithoutAction() {
        var json = """
                {"type": "INSIGHT", "title": "Progress update", "body": "3 issues closed"}""";
        var result = ActionResponseParser.parseAction(json);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForMalformedAction() {
        var json = """
                {"type": "SUGGESTION", "title": "Bad", "body": "x",
                 "action": {"category": "INVALID"}}""";
        var result = ActionResponseParser.parseAction(json);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForNoneResponse() {
        var json = """
                {"none": true}""";
        var result = ActionResponseParser.parseAction(json);
        assertTrue(result.isEmpty());
    }

    @Test
    void parsesAdvisoryAction() {
        var json = """
                {"type": "SUGGESTION", "title": "Prioritise #5", "body": "Unblocks 3",
                 "actionKey": "a2",
                 "action": {
                   "category": "ADVISORY",
                   "actionType": "advisory.prioritise",
                   "params": {"issueKey": "#5", "reason": "unblocks 3 issues"},
                   "rationale": "Critical path analysis"
                 }}""";
        var result = ActionResponseParser.parseAction(json);
        assertTrue(result.isPresent());
        assertEquals(ActionCategory.ADVISORY, result.get().category());
    }
}
