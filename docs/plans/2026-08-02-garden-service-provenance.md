# Garden Service + Provenance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #14 — Trellis: Garden Service + Provenance (B6a, post-MVP)
**Issue group:** #14

**Goal:** Add garden search, browse, and provenance tracking to trellis via the hortora engine, with provenance recording in soredium skills.

**Architecture:** Engine-centralised — engine owns all garden data (entries in Qdrant, provenance in SQLite). Trellis is a pure UI client calling engine REST APIs. Skills record provenance via MCP tool. Three repos touched: engine (data layer), soredium (producers), trellis (consumers).

**Tech Stack:** Java 21/25, Quarkus, SQLite, Flyway, HikariCP, REST-assured, WireMock, TypeScript, Lit, esbuild

## Global Constraints

- Engine groupId: `io.hortora`, package: `io.hortora.garden`
- Trellis groupId: `io.hortora`, package: `io.hortora.trellis`
- Trellis sidecar: single Maven module at `sidecar/pom.xml`
- Engine: single Maven module, Java 25, Quarkus 3.36.x
- Provenance SQLite DB path: `~/.hortora/stats/provenance.db` (configurable)
- All engine tests use `InMemoryCaseRetriever` and `InMemoryEmbeddingIngestor` (no Qdrant needed)
- Trellis tests use WireMock for engine REST client stubbing
- Soredium skill changes are Python/markdown — no automated tests (manual validation)
- Commits reference issue: `Refs #14`

---

### Task 1: Engine — ProvenanceStore + types + Flyway migration

**Repo:** Hortora/engine
**Files:**
- Create: `src/main/java/io/hortora/garden/provenance/ProvenanceRecord.java`
- Create: `src/main/java/io/hortora/garden/provenance/ProvenanceStats.java`
- Create: `src/main/java/io/hortora/garden/provenance/EntryRefCount.java`
- Create: `src/main/java/io/hortora/garden/provenance/ProvenanceConfig.java`
- Create: `src/main/java/io/hortora/garden/provenance/ProvenanceStore.java`
- Create: `src/main/resources/db/provenance/migration/V1__provenance.sql`
- Modify: `src/main/resources/application.properties` (add config)
- Test: `src/test/java/io/hortora/garden/provenance/ProvenanceStoreTest.java`

**Interfaces:**
- Consumes: nothing (foundational task)
- Produces: `ProvenanceStore.record(String issueRepo, int issueNumber, String specName, List<String> geIds, String recordedBy)`, `ProvenanceStore.forwardLineage(String issueRepo, int issueNumber) → List<ProvenanceRecord>`, `ProvenanceStore.reverseLineage(String geId) → List<ProvenanceRecord>`, `ProvenanceStore.stats() → ProvenanceStats`

- [ ] **Step 1: Write Flyway migration**

Create `src/main/resources/db/provenance/migration/V1__provenance.sql`:

```sql
CREATE TABLE provenance (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    issue_repo TEXT NOT NULL,
    issue_number INTEGER NOT NULL,
    ge_id TEXT NOT NULL,
    spec_name TEXT NOT NULL DEFAULT '',
    recorded_at TEXT NOT NULL,
    recorded_by TEXT,
    UNIQUE(issue_repo, issue_number, ge_id)
);

CREATE INDEX idx_provenance_issue ON provenance(issue_repo, issue_number);
CREATE INDEX idx_provenance_ge ON provenance(ge_id);
```

- [ ] **Step 2: Create record types**

Create `ProvenanceRecord.java`:
```java
package io.hortora.garden.provenance;

public record ProvenanceRecord(
    String issueRepo,
    int issueNumber,
    String specName,
    String geId,
    String recordedAt,
    String recordedBy) {}
```

Create `EntryRefCount.java`:
```java
package io.hortora.garden.provenance;

public record EntryRefCount(String geId, int referenceCount) {}
```

Create `ProvenanceStats.java`:
```java
package io.hortora.garden.provenance;

import java.util.List;

public record ProvenanceStats(
    int totalRecords,
    int uniqueEntries,
    int uniqueIssues,
    List<EntryRefCount> topReferenced,
    int unreferencedCount) {}
```

- [ ] **Step 3: Create ProvenanceConfig**

```java
package io.hortora.garden.provenance;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "hortora.garden.provenance")
public interface ProvenanceConfig {
    @WithDefault("${user.home}/.hortora/stats/provenance.db")
    String sqlitePath();

    @WithDefault("3")
    int sqlitePoolMaxSize();

    @WithDefault("5000")
    int sqliteBusyTimeoutMs();
}
```

- [ ] **Step 4: Add config to application.properties**

Add to `src/main/resources/application.properties`:
```properties
# Provenance tracking — SQLite
%test.hortora.garden.provenance.sqlite-path=:memory:
```

- [ ] **Step 5: Write failing tests for ProvenanceStore**

Create `src/test/java/io/hortora/garden/provenance/ProvenanceStoreTest.java`:

```java
package io.hortora.garden.provenance;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ProvenanceStoreTest {

    @Inject ProvenanceStore store;

    @BeforeEach
    void clear() {
        store.deleteAll();
    }

    @Test
    void recordAndForwardLineage() {
        store.record("Hortora/trellis", 14, "", List.of("GE-0031", "GE-0045"), "brainstorming");

        List<ProvenanceRecord> lineage = store.forwardLineage("Hortora/trellis", 14);
        assertEquals(2, lineage.size());
        assertTrue(lineage.stream().anyMatch(r -> r.geId().equals("GE-0031")));
        assertTrue(lineage.stream().anyMatch(r -> r.geId().equals("GE-0045")));
    }

    @Test
    void recordIsIdempotent() {
        store.record("Hortora/trellis", 14, "", List.of("GE-0031"), "brainstorming");
        store.record("Hortora/trellis", 14, "", List.of("GE-0031"), "brainstorming");

        List<ProvenanceRecord> lineage = store.forwardLineage("Hortora/trellis", 14);
        assertEquals(1, lineage.size());
    }

    @Test
    void upsertUpdatesSpecName() {
        store.record("Hortora/trellis", 14, "", List.of("GE-0031"), "work-start");
        store.record("Hortora/trellis", 14, "2026-08-02-design.md", List.of("GE-0031"), "brainstorming");

        List<ProvenanceRecord> lineage = store.forwardLineage("Hortora/trellis", 14);
        assertEquals(1, lineage.size());
        assertEquals("2026-08-02-design.md", lineage.getFirst().specName());
    }

    @Test
    void upsertDoesNotClearSpecName() {
        store.record("Hortora/trellis", 14, "spec.md", List.of("GE-0031"), "brainstorming");
        store.record("Hortora/trellis", 14, "", List.of("GE-0031"), "work-start");

        List<ProvenanceRecord> lineage = store.forwardLineage("Hortora/trellis", 14);
        assertEquals("spec.md", lineage.getFirst().specName());
    }

    @Test
    void reverseLineage() {
        store.record("Hortora/trellis", 14, "", List.of("GE-0031"), "brainstorming");
        store.record("Hortora/engine", 42, "", List.of("GE-0031"), "work-start");

        List<ProvenanceRecord> reverse = store.reverseLineage("GE-0031");
        assertEquals(2, reverse.size());
    }

    @Test
    void stats() {
        store.record("Hortora/trellis", 14, "", List.of("GE-0031", "GE-0045"), "brainstorming");
        store.record("Hortora/engine", 42, "", List.of("GE-0031"), "work-start");

        ProvenanceStats stats = store.stats();
        assertEquals(3, stats.totalRecords());
        assertEquals(2, stats.uniqueEntries());
        assertEquals(2, stats.uniqueIssues());
        assertFalse(stats.topReferenced().isEmpty());
        assertEquals("GE-0031", stats.topReferenced().getFirst().geId());
        assertEquals(2, stats.topReferenced().getFirst().referenceCount());
    }

    @Test
    void forwardLineageEmptyForUnknownIssue() {
        List<ProvenanceRecord> lineage = store.forwardLineage("Hortora/unknown", 999);
        assertTrue(lineage.isEmpty());
    }

    @Test
    void reverseLineageEmptyForUnknownEntry() {
        List<ProvenanceRecord> reverse = store.reverseLineage("GE-NONEXISTENT");
        assertTrue(reverse.isEmpty());
    }
}
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f <engine-path>/pom.xml test -Dtest=ProvenanceStoreTest`
Expected: compilation failure — `ProvenanceStore` does not exist

- [ ] **Step 7: Implement ProvenanceStore**

Create `src/main/java/io/hortora/garden/provenance/ProvenanceStore.java`. Follow the `SqliteRetrievalTracker` pattern: `@ApplicationScoped`, `HikariDataSource` in `@PostConstruct`, Flyway migration, `@PreDestroy` close.

Key implementation details:
- UPSERT: `INSERT INTO provenance (...) ON CONFLICT(issue_repo, issue_number, ge_id) DO UPDATE SET spec_name = CASE WHEN excluded.spec_name != '' THEN excluded.spec_name ELSE provenance.spec_name END, recorded_at = excluded.recorded_at`
- `deleteAll()` method for test cleanup
- `stats()` queries: `SELECT COUNT(*)`, `SELECT COUNT(DISTINCT ge_id)`, `SELECT COUNT(DISTINCT issue_repo || '#' || issue_number)`, `SELECT ge_id, COUNT(*) ... GROUP BY ge_id ORDER BY COUNT(*) DESC LIMIT 10`

- [ ] **Step 8: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f <engine-path>/pom.xml test -Dtest=ProvenanceStoreTest`
Expected: all 8 tests PASS

- [ ] **Step 9: Commit**

```bash
git -C <engine-path> add src/main/java/io/hortora/garden/provenance/ src/main/resources/db/provenance/ src/main/resources/application.properties src/test/java/io/hortora/garden/provenance/
git -C <engine-path> commit -m "feat(#14): add ProvenanceStore — SQLite storage for garden provenance tracking"
```

---

### Task 2: Engine — ProvenanceResource (REST endpoints)

**Repo:** Hortora/engine
**Files:**
- Create: `src/main/java/io/hortora/garden/provenance/ProvenanceResource.java`
- Create: `src/main/java/io/hortora/garden/provenance/ProvenanceRecordRequest.java`
- Test: `src/test/java/io/hortora/garden/provenance/ProvenanceResourceTest.java`

**Interfaces:**
- Consumes: `ProvenanceStore` (Task 1)
- Produces: REST endpoints `POST /provenance`, `GET /provenance`, `GET /provenance/reverse`, `GET /provenance/stats`

- [ ] **Step 1: Create request body type**

```java
package io.hortora.garden.provenance;

import java.util.List;

public record ProvenanceRecordRequest(
    String issueRepo,
    int issueNumber,
    String specName,
    List<String> geIds,
    String recordedBy) {}
```

- [ ] **Step 2: Write failing REST-assured tests**

Create `src/test/java/io/hortora/garden/provenance/ProvenanceResourceTest.java`:

```java
package io.hortora.garden.provenance;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ProvenanceResourceTest {

    @Inject ProvenanceStore store;

    @BeforeEach
    void clear() { store.deleteAll(); }

    @Test
    void postRecordsProvenance() {
        given().contentType(ContentType.JSON)
            .body(new ProvenanceRecordRequest("Hortora/trellis", 14,
                "spec.md", List.of("GE-0031", "GE-0045"), "brainstorming"))
            .when().post("/provenance")
            .then().statusCode(201)
            .body("recorded", equalTo(2));
    }

    @Test
    void forwardLineage() {
        store.record("Hortora/trellis", 14, "", List.of("GE-0031"), "brainstorming");

        given().queryParam("issueRepo", "Hortora/trellis")
            .queryParam("issueNumber", 14)
            .when().get("/provenance")
            .then().statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].geId", equalTo("GE-0031"));
    }

    @Test
    void reverseLineage() {
        store.record("Hortora/trellis", 14, "", List.of("GE-0031"), "brainstorming");

        given().queryParam("geId", "GE-0031")
            .when().get("/provenance/reverse")
            .then().statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].issueRepo", equalTo("Hortora/trellis"));
    }

    @Test
    void stats() {
        store.record("Hortora/trellis", 14, "", List.of("GE-0031", "GE-0045"), "brainstorming");

        given().when().get("/provenance/stats")
            .then().statusCode(200)
            .body("totalRecords", equalTo(2))
            .body("uniqueEntries", equalTo(2));
    }

    @Test
    void postMissingIssueRepoReturns400() {
        given().contentType(ContentType.JSON)
            .body("{\"issueNumber\":14,\"geIds\":[\"GE-0031\"]}")
            .when().post("/provenance")
            .then().statusCode(400);
    }

    @Test
    void postEmptyGeIdsReturns400() {
        given().contentType(ContentType.JSON)
            .body(new ProvenanceRecordRequest("Hortora/trellis", 14,
                "", List.of(), "brainstorming"))
            .when().post("/provenance")
            .then().statusCode(400);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f <engine-path>/pom.xml test -Dtest=ProvenanceResourceTest`
Expected: 404 on all endpoints

- [ ] **Step 4: Implement ProvenanceResource**

```java
package io.hortora.garden.provenance;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

@Path("/provenance")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class ProvenanceResource {

    @Inject ProvenanceStore store;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response record(ProvenanceRecordRequest request) {
        if (request.issueRepo() == null || request.issueRepo().isBlank()) {
            return Response.status(400).entity("{\"error\":\"issueRepo is required\"}").build();
        }
        if (request.geIds() == null || request.geIds().isEmpty()) {
            return Response.status(400).entity("{\"error\":\"geIds must not be empty\"}").build();
        }
        String specName = request.specName() != null ? request.specName() : "";
        int count = store.record(request.issueRepo(), request.issueNumber(),
            specName, request.geIds(), request.recordedBy());
        return Response.status(201).entity("{\"recorded\":" + count + "}").build();
    }

    @GET
    public List<ProvenanceRecord> forwardLineage(
            @QueryParam("issueRepo") String issueRepo,
            @QueryParam("issueNumber") int issueNumber) {
        return store.forwardLineage(issueRepo, issueNumber);
    }

    @GET @Path("/reverse")
    public List<ProvenanceRecord> reverseLineage(@QueryParam("geId") String geId) {
        return store.reverseLineage(geId);
    }

    @GET @Path("/stats")
    public ProvenanceStats stats() {
        return store.stats();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f <engine-path>/pom.xml test -Dtest=ProvenanceResourceTest`
Expected: all 6 tests PASS

- [ ] **Step 6: Commit**

```bash
git -C <engine-path> add src/main/java/io/hortora/garden/provenance/ProvenanceResource.java src/main/java/io/hortora/garden/provenance/ProvenanceRecordRequest.java src/test/java/io/hortora/garden/provenance/
git -C <engine-path> commit -m "feat(#14): add ProvenanceResource — REST endpoints for provenance CRUD"
```

---

### Task 3: Engine — gardenRecordProvenance MCP tool

**Repo:** Hortora/engine
**Files:**
- Modify: `src/main/java/io/hortora/garden/mcp/GardenMcpTools.java`
- Modify: `src/test/java/io/hortora/garden/mcp/GardenMcpToolsTest.java`

**Interfaces:**
- Consumes: `ProvenanceStore` (Task 1)
- Produces: MCP tool `gardenRecordProvenance(issueRepo, issueNumber, specName?, geIds, recordedBy?)`

- [ ] **Step 1: Write failing tests**

Add to `GardenMcpToolsTest.java`:

```java
@Test
void gardenRecordProvenanceRecordsEntries() {
    String result = mcpTools.gardenRecordProvenance(
        "Hortora/trellis", 14, null, "GE-0031|GE-0045", "brainstorming");
    assertTrue(result.contains("2"));
    
    List<ProvenanceRecord> lineage = provenanceStore.forwardLineage("Hortora/trellis", 14);
    assertEquals(2, lineage.size());
}

@Test
void gardenRecordProvenanceFiltersEmptySegments() {
    String result = mcpTools.gardenRecordProvenance(
        "Hortora/trellis", 14, null, "|GE-0031||GE-0045|", "brainstorming");
    assertTrue(result.contains("2"));
}

@Test
void gardenRecordProvenanceRejectsAllEmptyIds() {
    String result = mcpTools.gardenRecordProvenance(
        "Hortora/trellis", 14, null, "|||", "brainstorming");
    assertTrue(result.toLowerCase().contains("error"));
}

@Test
void gardenRecordProvenanceCoercesNullSpecNameToEmpty() {
    mcpTools.gardenRecordProvenance("Hortora/trellis", 14, null, "GE-0031", "brainstorming");
    
    List<ProvenanceRecord> lineage = provenanceStore.forwardLineage("Hortora/trellis", 14);
    assertEquals("", lineage.getFirst().specName());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f <engine-path>/pom.xml test -Dtest=GardenMcpToolsTest#gardenRecordProvenance*`
Expected: compilation failure — method does not exist

- [ ] **Step 3: Implement gardenRecordProvenance**

Add `@Inject ProvenanceStore provenanceStore;` field to `GardenMcpTools`.

Add the MCP tool method matching the spec §3.4 signature. Parse pipe-separated `geIds`, filter empty segments, coerce null `specName` to `""`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f <engine-path>/pom.xml test -Dtest=GardenMcpToolsTest`
Expected: all tests PASS (including existing tests — no regressions)

- [ ] **Step 5: Commit**

```bash
git -C <engine-path> add src/main/java/io/hortora/garden/mcp/GardenMcpTools.java src/test/java/io/hortora/garden/mcp/GardenMcpToolsTest.java
git -C <engine-path> commit -m "feat(#14): add gardenRecordProvenance MCP tool"
```

---

### Task 4: Engine — Adaptive search + entry lookup REST endpoints

**Repo:** Hortora/engine
**Files:**
- Modify: `src/main/java/io/hortora/garden/search/SearchResource.java`
- Modify: `src/main/java/io/hortora/garden/search/AdaptiveResult.java`
- Create: `src/main/java/io/hortora/garden/search/EntryDetail.java`
- Create: `src/main/java/io/hortora/garden/search/EntryResource.java`
- Modify: `src/test/java/io/hortora/garden/search/SearchResourceTest.java`
- Create: `src/test/java/io/hortora/garden/search/EntryResourceTest.java`

**Interfaces:**
- Consumes: existing `SearchResource.searchAdaptive()`, `EmbeddingIngestor`
- Produces: `GET /search/adaptive`, `GET /entries/{id}`

- [ ] **Step 1: Add `collectionReady` to AdaptiveResult**

Modify `AdaptiveResult.java` — add `boolean collectionReady` field. Update existing static factory/constructor calls. Default `true`.

- [ ] **Step 2: Create EntryDetail record**

```java
package io.hortora.garden.search;

import java.util.List;

public record EntryDetail(
    String id, String title, String domain, String type,
    int score, String body, String source, String sourcePrefix,
    List<String> seeAlsoIds) {}
```

- [ ] **Step 3: Write failing tests for adaptive search endpoint**

Add to `SearchResourceTest.java`:

```java
@Test
void adaptiveSearchEndpoint() {
    given().queryParam("q", "quarkus packaging")
        .when().get("/search/adaptive")
        .then().statusCode(200)
        .body("results.size()", greaterThan(0))
        .body("collectionReady", equalTo(true));
}

@Test
void adaptiveSearchMissingQueryReturns400() {
    given().when().get("/search/adaptive")
        .then().statusCode(400);
}
```

- [ ] **Step 4: Write failing tests for entry lookup**

Create `EntryResourceTest.java`:

```java
package io.hortora.garden.search;

import io.quarkus.test.junit.QuarkusTest;
import io.casehub.neocortex.rag.EmbeddingIngestor;
import io.casehub.neocortex.rag.CorpusRef;
import io.hortora.garden.config.GardenConfig;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntryResourceTest {

    @Inject EmbeddingIngestor ingestor;
    @Inject GardenConfig config;

    @BeforeAll
    void seed() {
        // Seed a test entry using InMemoryEmbeddingIngestor
        CorpusRef corpus = new CorpusRef("hortora", config.id());
        ingestor.ingest(corpus, "jvm/GE-0031.md",
            "GE-0031 title", "body content",
            java.util.Map.of("domain", "jvm", "type", "undocumented",
                "score", "12", "source", config.id()));
    }

    @Test
    void getEntryByGeId() {
        given().when().get("/entries/GE-0031")
            .then().statusCode(200)
            .body("id", equalTo("GE-0031"))
            .body("domain", equalTo("jvm"));
    }

    @Test
    void getEntryNotFound() {
        given().when().get("/entries/GE-NONEXISTENT")
            .then().statusCode(404);
    }
}
```

- [ ] **Step 5: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f <engine-path>/pom.xml test -Dtest=SearchResourceTest#adaptiveSearch*,EntryResourceTest`
Expected: 404 / compilation errors

- [ ] **Step 6: Implement adaptive search endpoint on SearchResource**

Add a new `@GET @Path("/adaptive")` method on `SearchResource` that delegates to the existing `searchAdaptive()` method. Wrap with try/catch for Qdrant unavailability — return `AdaptiveResult` with `collectionReady: false` on exception.

- [ ] **Step 7: Implement EntryResource**

Create `EntryResource.java` at `@Path("/entries")`. Uses `EmbeddingIngestor.listDocuments()` to build a `geId → sourceDocumentId` mapping. Cache the mapping. Lookup the entry by source document ID, convert `SearchResult` to `EntryDetail`. Return 404 if not found.

- [ ] **Step 8: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f <engine-path>/pom.xml test -Dtest=SearchResourceTest,EntryResourceTest`
Expected: all tests PASS

- [ ] **Step 9: Run full engine test suite**

Run: `/opt/homebrew/bin/mvn -f <engine-path>/pom.xml test`
Expected: all tests PASS (no regressions)

- [ ] **Step 10: Commit**

```bash
git -C <engine-path> add src/main/java/io/hortora/garden/search/ src/test/java/io/hortora/garden/search/
git -C <engine-path> commit -m "feat(#14): add adaptive search and entry lookup REST endpoints"
```

---

### Task 5: Soredium — Provenance recording in brainstorming and work-start skills

**Repo:** Hortora/soredium
**Files:**
- Modify: `brainstorming/SKILL.md`
- Modify: `work-start/SKILL.md`

**Interfaces:**
- Consumes: `gardenRecordProvenance` MCP tool (Task 3)
- Produces: provenance records in engine SQLite (triggered during skill execution)

- [ ] **Step 1: Modify brainstorming skill**

In `brainstorming/SKILL.md`, find the section in Step 1 (Gather context) where garden entries are surfaced. After "carry it forward into brainstorming and implementation", add:

```markdown
**Record provenance:** After the user selects relevant entries, call:

```
gardenRecordProvenance(
    issueRepo=<from .meta issue-repo field>,
    issueNumber=<from .meta issue field>,
    specName="",
    geIds=<pipe-separated selected GE-IDs>,
    recordedBy="brainstorming"
)
```

If `gardenRecordProvenance` is unavailable (engine not running), warn once
and continue — provenance recording is never a gate on work.

When the spec is written (Step 5 — Write design doc), record again with
`specName` set to the spec filename:

```
gardenRecordProvenance(
    issueRepo=<from .meta>,
    issueNumber=<from .meta>,
    specName=<spec filename>,
    geIds=<pipe-separated selected GE-IDs>,
    recordedBy="brainstorming"
)
```
```

- [ ] **Step 2: Modify work-start skill**

In `work-start/SKILL.md`, find Step 3b (Garden search). After "carry it forward", add:

```markdown
**Record provenance:** After the user selects relevant entries, call:

```
gardenRecordProvenance(
    issueRepo=<ISSUE_REPO_GITHUB from Step 4>,
    issueNumber=<ISSUE_N from Step 4>,
    specName="",
    geIds=<pipe-separated selected GE-IDs>,
    recordedBy="work-start"
)
```

If `gardenRecordProvenance` is unavailable, warn once and continue.
```

- [ ] **Step 3: Commit soredium changes**

```bash
git -C <soredium-path> add brainstorming/SKILL.md work-start/SKILL.md
git -C <soredium-path> commit -m "feat(#14): add provenance recording to brainstorming and work-start skills"
```

- [ ] **Step 4: Sync skills**

Run `sync-local` to install the updated skills.

- [ ] **Step 5: Manual validation**

Start a brainstorming session on a test issue. Verify:
1. Garden entries are surfaced and user is asked which are relevant
2. `gardenRecordProvenance` is called with correct parameters
3. Engine SQLite DB contains the provenance records

---

### Task 6: Trellis — GardenClient + types + pom dependency

**Repo:** Hortora/trellis
**Files:**
- Modify: `sidecar/pom.xml`
- Create: `sidecar/src/main/java/io/hortora/trellis/garden/GardenClient.java`
- Create: `sidecar/src/main/java/io/hortora/trellis/garden/AdaptiveSearchResponse.java`
- Create: `sidecar/src/main/java/io/hortora/trellis/garden/GardenSearchResult.java`
- Create: `sidecar/src/main/java/io/hortora/trellis/garden/EntryDetail.java`
- Create: `sidecar/src/main/java/io/hortora/trellis/garden/ProvenanceRecord.java`
- Create: `sidecar/src/main/java/io/hortora/trellis/garden/ProvenanceStats.java`
- Create: `sidecar/src/main/java/io/hortora/trellis/garden/EntryRefCount.java`
- Modify: `sidecar/src/main/resources/application.properties`
- Test: `sidecar/src/test/java/io/hortora/trellis/garden/GardenClientTest.java`

**Interfaces:**
- Consumes: Engine REST endpoints (Tasks 1-4)
- Produces: `GardenClient` interface for `GardenResource` (Task 7)

- [ ] **Step 1: Add quarkus-rest-client-jackson to pom.xml**

Add to `sidecar/pom.xml` dependencies:
```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-rest-client-jackson</artifactId>
</dependency>
```

- [ ] **Step 2: Add engine URL config**

Add to `sidecar/src/main/resources/application.properties`:
```properties
# Garden engine REST client
quarkus.rest-client.garden-engine.url=http://localhost:8080
quarkus.rest-client.garden-engine.connect-timeout=2000
quarkus.rest-client.garden-engine.read-timeout=10000
```

- [ ] **Step 3: Create client-side types**

Create the mirror types in `io.hortora.trellis.garden`:
- `GardenSearchResult` — mirrors engine's `SearchResult` (id, title, domain, type, score, body, relevance, crossEncoderScore, source, sourcePrefix)
- `AdaptiveSearchResponse` — mirrors engine's `AdaptiveResult` (results, requestedLimit, availableAboveFloor, extended, trimmed, floorFiltered, collectionReady)
- `EntryDetail` — mirrors engine's `EntryDetail`
- `ProvenanceRecord` — mirrors engine's `ProvenanceRecord`
- `ProvenanceStats` — mirrors engine's `ProvenanceStats`
- `EntryRefCount` — mirrors engine's `EntryRefCount`

- [ ] **Step 4: Create GardenClient interface**

```java
package io.hortora.trellis.garden;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import java.util.List;

@RegisterRestClient(configKey = "garden-engine")
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public interface GardenClient {

    @GET @Path("/search/adaptive")
    AdaptiveSearchResponse search(
        @QueryParam("q") String query,
        @QueryParam("keywords") String keywords,
        @QueryParam("domain") List<String> domains,
        @QueryParam("type") String type,
        @QueryParam("tags") String tags,
        @QueryParam("limit") Integer limit);

    @GET @Path("/entries/{id}")
    EntryDetail getEntry(@PathParam("id") String geId);

    @GET @Path("/provenance")
    List<ProvenanceRecord> forwardLineage(
        @QueryParam("issueRepo") String repo,
        @QueryParam("issueNumber") int number);

    @GET @Path("/provenance/reverse")
    List<ProvenanceRecord> reverseLineage(@QueryParam("geId") String geId);

    @GET @Path("/provenance/stats")
    ProvenanceStats stats();
}
```

- [ ] **Step 5: Write WireMock test for GardenClient**

Create `GardenClientTest.java` — `@QuarkusTest` with WireMock stubbing the engine endpoints. Verify deserialization of search results, provenance records, and error handling.

- [ ] **Step 6: Run tests**

Run: `/opt/homebrew/bin/mvn -f sidecar/pom.xml test -Dtest=GardenClientTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add sidecar/pom.xml sidecar/src/main/java/io/hortora/trellis/garden/ sidecar/src/main/resources/application.properties sidecar/src/test/java/io/hortora/trellis/garden/
git commit -m "feat(#14): add GardenClient REST client and types for engine integration"
```

---

### Task 7: Trellis — GardenResource + ProvenanceEnricher

**Repo:** Hortora/trellis
**Files:**
- Create: `sidecar/src/main/java/io/hortora/trellis/garden/GardenResource.java`
- Create: `sidecar/src/main/java/io/hortora/trellis/garden/ProvenanceEnricher.java`
- Create: `sidecar/src/main/java/io/hortora/trellis/garden/EnrichedProvenanceRecord.java`
- Create: `sidecar/src/main/java/io/hortora/trellis/garden/WorkspaceContext.java`
- Test: `sidecar/src/test/java/io/hortora/trellis/garden/GardenResourceTest.java`
- Test: `sidecar/src/test/java/io/hortora/trellis/garden/ProvenanceEnricherTest.java`

**Interfaces:**
- Consumes: `GardenClient` (Task 6), `WorkspaceScanner` (existing)
- Produces: REST endpoints `GET /api/garden/search`, `GET /api/garden/entries/{id}`, `GET /api/garden/provenance`, `GET /api/garden/provenance/reverse`, `GET /api/garden/stats`

- [ ] **Step 1: Create enrichment types**

```java
package io.hortora.trellis.garden;

import java.util.List;

public record WorkspaceContext(int slotNumber, String slotStatus, List<String> repos) {}

public record EnrichedProvenanceRecord(
    String issueRepo, int issueNumber, String specName,
    String geId, String recordedAt, String recordedBy,
    WorkspaceContext workspace) {}
```

- [ ] **Step 2: Write failing tests for ProvenanceEnricher**

Create `ProvenanceEnricherTest.java` — unit test (not `@QuarkusTest`). Mock `WorkspaceScanner`, test enrichment with matching slot, no matching slot, and scanner failure.

- [ ] **Step 3: Implement ProvenanceEnricher**

`@ApplicationScoped` bean that takes `WorkspaceScanner`, correlates `issueRepo + "#" + issueNumber` against `SlotInfo.issue` from scanner data. Returns `EnrichedProvenanceRecord` list. Catches exceptions from scanner — returns unenriched records on failure (WARN log).

- [ ] **Step 4: Run ProvenanceEnricher tests**

Expected: PASS

- [ ] **Step 5: Write failing tests for GardenResource**

Create `GardenResourceTest.java` — `@QuarkusTest` with WireMock for engine, test all 5 endpoints. Verify enrichment on provenance endpoints.

- [ ] **Step 6: Implement GardenResource**

`@Path("/api/garden")`, `@ApplicationScoped`. Injects `GardenClient` (via `@RestClient`) and `ProvenanceEnricher`. Delegates search/entry calls directly. Enriches provenance results. Uses `@Retry(maxRetries=2, delay=500, jitter=200)` and `@Fallback` — fallback returns error response with `available: false`.

- [ ] **Step 7: Run all trellis tests**

Run: `/opt/homebrew/bin/mvn -f sidecar/pom.xml test`
Expected: all PASS

- [ ] **Step 8: Commit**

```bash
git add sidecar/src/main/java/io/hortora/trellis/garden/ sidecar/src/test/java/io/hortora/trellis/garden/
git commit -m "feat(#14): add GardenResource with provenance enrichment"
```

---

### Task 8: Trellis — Garden View UI

**Repo:** Hortora/trellis
**Files:**
- Create: `sidecar/src/main/webui/src/views/garden-view.ts`
- Create: `sidecar/src/main/webui/src/components/garden-entry-detail.ts`
- Create: `sidecar/src/main/webui/src/components/garden-search-results.ts`
- Modify: `sidecar/src/main/webui/src/app.ts` (add route)

**Interfaces:**
- Consumes: `GET /api/garden/search`, `GET /api/garden/entries/{id}`, `GET /api/garden/provenance/reverse`, `GET /api/garden/stats` (Task 7)
- Produces: Garden View accessible at `#garden` route

- [ ] **Step 1: Create garden-search-results component**

Lit component (`trellis-garden-search-results`) showing a list of search results. Each item: title, domain badge, type badge, relevance score. Click emits `entry-selected` custom event with GE-ID.

- [ ] **Step 2: Create garden-entry-detail component**

Lit component (`trellis-garden-entry-detail`) displaying:
- Rendered markdown body (use existing markdown rendering if available, otherwise innerHTML with sanitisation)
- Metadata bar: domain, type, score
- Usage map section: fetches reverse provenance, shows linked issues

- [ ] **Step 3: Create garden-view page**

Lit component (`trellis-garden-view`) composing:
- Search input with domain/type filter dropdowns
- `trellis-garden-search-results` for results list
- `trellis-garden-entry-detail` for selected entry
- Stats section (top-referenced entries, domain distribution)

Fetch pattern: `fetch('/api/garden/search?q=...')` on form submit. Show loading state. Show "Garden unavailable" when `available: false` in response.

- [ ] **Step 4: Add route to app.ts**

Add `#garden` route handling in `app.ts`:
```typescript
} else if (hash.match(/^#garden/)) {
    const view = document.createElement('trellis-garden-view');
    container.appendChild(view);
}
```

Add import: `import "./views/garden-view";`

- [ ] **Step 5: Manual testing**

Start dev server: `/opt/homebrew/bin/mvn -f sidecar/pom.xml quarkus:dev`
Navigate to `#garden`. Verify:
1. Search works (requires engine running)
2. Entry detail renders markdown
3. Provenance usage map shows linked issues
4. Degradation: stop engine → "Garden unavailable" message
5. Stats section populates

- [ ] **Step 6: Commit**

```bash
git add sidecar/src/main/webui/src/views/garden-view.ts sidecar/src/main/webui/src/components/garden-entry-detail.ts sidecar/src/main/webui/src/components/garden-search-results.ts sidecar/src/main/webui/src/app.ts
git commit -m "feat(#14): add Garden View UI — search, browse, provenance lineage"
```

---

## Task Dependencies

```
Task 1 (ProvenanceStore) ──→ Task 2 (ProvenanceResource) ──→ Task 6 (GardenClient)
                         └──→ Task 3 (MCP tool)            └──→ Task 7 (GardenResource)
                                    └──→ Task 5 (Skills)        └──→ Task 8 (Garden View UI)
Task 4 (Search + Entry endpoints) ──→ Task 6 (GardenClient)
```

Tasks 1-4 are engine work (do first). Tasks 1-3 are sequential. Task 4 is independent of 1-3.
Task 5 (skills) depends on Task 3 (MCP tool exists).
Tasks 6-8 are trellis work (do after engine). Sequential: 6 → 7 → 8.
