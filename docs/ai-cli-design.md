# AI-friendly CLI design

Status: proposal  
Audience: OpenTowns maintainers and contributors  
Scope: the headless command interface in `xaos.cli` and `xaos.TownsHeadless`

## Summary

OpenTowns already has the hard parts of a useful testing interface: deterministic seeds, explicit ticking, a headless entry point, commands that use the real game-order pipeline, save support, state hashes, and inspection commands. The next step should not be a second game API. It should be a stable machine protocol over the existing `GameCommandShell`.

The recommended design is a versioned newline-delimited JSON (NDJSON) mode with four core operations:

1. **Discover** the available commands, schemas, IDs, and game capabilities.
2. **Observe** a bounded, queryable snapshot of relevant state.
3. **Act** through the same order and production paths used by the player.
4. **Advance** deterministically until a condition, limit, or failure is reached.

Assertions, structured errors, event deltas, and reproducible transcripts make those operations useful for both autonomous play and regression tests. Existing text commands remain available for humans and current scripts.

## Problem statement

An AI playing through the current CLI must repeatedly parse presentation-oriented strings, guess which catalog entries are valid parameters, issue large fixed tick counts, and infer whether an order is merely queued, blocked, completed, or silently made irrelevant. The command catalog is useful to a human but does not completely describe argument types, response fields, or possible errors. Multi-line responses share the same `[TownsHeadless]` prefix as lifecycle logging, which makes framing and correlation difficult.

This creates several failure modes:

- output parsing breaks when labels or spacing change;
- a model consumes excessive context by repeatedly listing whole collections;
- fixed `tick N` calls overshoot interesting transitions and waste runtime;
- entity IDs can be observed, but their stability and relationships are not expressed as a contract;
- failed preconditions are returned as prose instead of actionable error codes;
- successful command execution does not necessarily mean the intended game outcome occurred;
- scripts stop at the first failure without a structured transcript of the decision loop;
- tests assert substrings rather than typed values and protocol invariants.

The target experience is an agent loop that can ask "what changed?", choose a legal action from discoverable schemas, advance until a stated condition, and prove the result without inspecting Java internals.

## Goals

- Make every response unambiguous, bounded, typed, and correlated with its request.
- Keep simulation deterministic and make determinism metadata visible.
- Expose the game model needed for decisions without exposing mutable Java objects.
- Use real player action pipelines; debug-only mutation must be explicit and off by default.
- Support both exploratory AI play and concise, repeatable automated tests.
- Allow partial implementation while preserving all existing commands and scripts.
- Keep prompts small by supporting filters, pagination, summaries, and deltas.

## Non-goals

- Replacing the windowed UI or the game simulation with a separate rules engine.
- Providing unrestricted spawning or direct state mutation in normal play mode.
- Designing an AI policy, planner, or model-specific prompt format.
- Guaranteeing that every internal field is part of the public protocol.
- Turning the CLI into a network service in the first implementation.

## Design principles

### One simulation seam

Commands must continue to enter through the same task, building, stockpile, zone, and production systems used by the player. A response should distinguish `accepted`, `started`, `completed`, and `failed`; it must not simulate success by directly editing state.

### Stable protocol, flexible presentation

Human-readable text may evolve. Machine mode is versioned and covered by compatibility tests. JSON field names and error codes are the API; English messages are explanatory only.

### Bounded by default

Every list has a default and maximum page size. Every advance operation has a tick and wall-clock limit. Every wait condition ends with a terminal reason. No request may accidentally dump the entire world or run forever.

### Observe changes, not noise

Snapshots include a monotonic simulation tick and revision. An agent can request changes since a prior revision instead of re-reading all entities. Ordering is deterministic.

### Errors should suggest the next valid move

Errors include a stable code, the offending field, relevant constraints, and optional suggestions. For example, an invalid building ID should return nearby catalog matches; an unreachable order should identify the failed precondition when the game can determine it.

## Proposed user interface

Keep the existing invocation unchanged and add a protocol selection:

```powershell
.\gradlew runHeadless -Pseed=42 -Pprotocol=json -Pinteractive=true -Pmode=test
```

JSON sessions may record canonical request/response exchanges and replay them
against a deterministic world:

```powershell
.\gradlew runHeadless -Pseed=42 -Pprotocol=json -Pinteractive=true -Ptranscript=tmp\session.ndjson
.\gradlew runHeadless -Pseed=42 -Preplay=tmp\session.ndjson
```

The protocol exposes `play`, `test`, and `debug` modes through `-Pmode`; normal
player actions remain available in every mode, while assertions and checkpoints
are restricted to test/debug sessions. Direct debug mutation is not enabled by
this implementation.

Also accept `--protocol=json` directly. Standard input contains one JSON object per line and standard output contains exactly one response object per request, plus explicitly typed asynchronous events when requested. Diagnostics and JVM/game logs go to standard error in machine mode.

Text mode remains the default during migration. A later release may make JSON the default only for a dedicated task such as `runAgent`.

### Request envelope

```json
{"id":"req-17","op":"observe","args":{"include":["summary","citizens","tasks"],"limit":50}}
```

Required fields:

- `id`: caller-selected string used to correlate the response;
- `op`: stable operation name;
- `args`: operation-specific object, optional when empty.

Optional fields include `protocol_version`, `if_revision`, and `timeout_ms`. Unknown fields should be rejected in strict mode and ignored with warnings in lenient mode.

### Response envelope

```json
{
  "id":"req-17",
  "ok":true,
  "protocol_version":"2.0",
  "tick":1200,
  "revision":83,
  "result":{},
  "warnings":[]
}
```

Failures use the same envelope:

```json
{
  "id":"req-18",
  "ok":false,
  "tick":1200,
  "revision":83,
  "error":{
    "code":"INVALID_ARGUMENT",
    "message":"Unknown building id 'bakr'.",
    "field":"building_id",
    "value":"bakr",
    "suggestions":["bakerstable","bakersoven"]
  }
}
```

Exit status is non-zero for startup, protocol, or unrecoverable simulation failure. Individual command failures in a continuing interactive session are represented by `ok:false`; batch mode may opt into `--fail-fast`.

## Core operations

### `capabilities`

Returns protocol versions, supported operations, world configuration, limits, and whether saving, debug actions, event streaming, and delta observation are enabled. This is the first request an agent should make.

### `describe`

Returns machine-readable schemas for operations and action types. Each action description includes required and optional arguments, types, ranges, enum/catalog references, examples, side effects, and expected lifecycle states.

Examples:

```json
{"id":"d1","op":"describe","args":{"action":"mine_area"}}
{"id":"d2","op":"describe","args":{"catalog":"buildings","filter":"baker"}}
```

This replaces the need to interpret the prose emitted by `commands`, while `help` and `commands` remain for people.

### `observe`

Returns a coherent snapshot at one simulation tick. Callers select sections rather than invoking many unrelated inspection commands:

- `summary`: date, population, inventory totals, coins, speed, pause state, tick, hashes;
- `map`: dimensions, discovered bounds, origin, view, selected regions or cells;
- `citizens`: identity, position, needs, job, current task, health, inventory;
- `items`: identity, type, position/container, quantity, reservation state;
- `tasks`: identity, type, target, assignee, state, blockers, creation tick;
- `buildings`, `zones`, `stockpiles`, and `production`;
- `objectives` and recent `events` when those systems are exposed.

All sections support documented filters, `limit`, and an opaque pagination cursor. Collection ordering must be stable, preferably by numeric ID then coordinates. Spatial queries support an axis-aligned box and `near` plus `radius` so an agent need not enumerate the world.

An optional `since_revision` returns added, updated, and removed records. Revisions advance only when observable state changes.

### `act`

Submits one player action with typed arguments:

```json
{
  "id":"a1",
  "op":"act",
  "args":{
    "action":"mine_area",
    "target":{"from":{"x":10,"y":20,"z":3},"to":{"x":12,"y":22,"z":3}},
    "client_tag":"open-stone-access"
  }
}
```

The result contains an `action_id`, created task/order IDs, normalized arguments, acceptance state, and any known warnings. `client_tag` is echoed through observations and events so an agent can track intent without relying on fragile list position.

Support `dry_run:true` to validate syntax, bounds, catalog IDs, known prerequisites, and estimated affected cells without mutating the simulation. Dry-run validation must be clearly described as best-effort when reachability or resource availability can change.

Batch actions should be atomic only for validation and submission: either all commands are accepted or none are submitted. Their eventual in-game outcomes remain independent.

### `advance`

Unify fixed ticking and conditional waiting:

```json
{
  "id":"t1",
  "op":"advance",
  "args":{
    "until":{"any":[
      {"event":"action.completed","client_tag":"open-stone-access"},
      {"event":"citizen.died"},
      {"condition":{"path":"tasks.blocked","op":"gt","value":0}}
    ]},
    "max_ticks":20000,
    "sample_every":100,
    "return":["summary","events","task_delta"]
  }
}
```

Terminal reasons are `condition_met`, `max_ticks`, `timeout`, `paused`, `game_over`, `quiescent`, or `error`. The response reports ticks advanced and the condition that matched. Conditions use a small documented expression tree, not arbitrary code or Java field names.

Retain `tick N` as text-mode shorthand for `advance` with only `max_ticks=N`.

### `assert`

Evaluates the same safe condition language without advancing:

```json
{"id":"v1","op":"assert","args":{"condition":{"path":"inventory.bread","op":"gte","value":10}}}
```

The response includes expected and actual values. In batch/test mode a failed assertion can set the process exit code while still emitting a complete response. Named domain assertions such as `food_ready` may wrap common conditions, but must expand to or document their precise predicates.

### `checkpoint`

Creates or loads a named save and returns its tick, revision, seed metadata, and state hash. Loading begins a new revision epoch so stale cursors and `if_revision` values fail clearly. A later in-memory checkpoint may speed tests, but disk saves are sufficient initially.

## Identifiers and state lifecycle

Every observable object needs a protocol identity with documented lifetime:

- use existing numeric entity IDs when they are deterministic and never reused within a world;
- give tasks, actions, zones, stockpiles, and production orders explicit IDs if they lack them;
- include `kind` alongside every ID to prevent cross-type ambiguity;
- use `client_tag` only as caller metadata, not as a unique primary key;
- identify a loaded/new world with a `world_id` derived from seed/map/load epoch, not solely a state hash.

Records should expose lifecycle states from a shared vocabulary where practical: `pending`, `accepted`, `active`, `blocked`, `completed`, `cancelled`, and `failed`. Domain-specific states may be included separately. Completed records remain queryable for a bounded history so an agent can learn why something disappeared.

## Events and diagnostics

The simulation should append compact structured events to a bounded ring buffer. Initial useful event types are:

- action/task accepted, started, blocked, unblocked, completed, cancelled, failed;
- item created, moved, consumed, or destroyed;
- building completed or production stalled;
- citizen spawned, injured, hungry, idle, or dead;
- day changed, objective changed, and game-over state reached.

Events include `event_id`, tick, type, involved typed IDs, coordinates when relevant, `client_tag`, and structured reason codes. They are queried through `observe`/`advance`; unsolicited streaming is optional because one-response-per-request is easier for most agent runners.

Known blockers should be normalized, for example `MISSING_RESOURCE`, `NO_PATH`, `NO_WORKER`, `NO_WORKSTATION`, `TARGET_RESERVED`, or `INVALID_TERRAIN`. Preserve a human explanation and relevant IDs alongside the code.

## Transcript and reproducibility

Machine mode can write a transcript with one canonical record per request and response. The transcript header contains:

- protocol and game version/commit;
- seed, map type, user-folder mode, and load source;
- Java/runtime version and determinism settings;
- initial terrain and state hashes.

The footer contains final hashes, ticks advanced, exit reason, error count, and assertion count. Secrets and absolute user paths must not be recorded by default. A `replay` mode should submit the recorded mutating requests and verify response shape, terminal reasons, and chosen hashes.

## Human text mode improvements

The JSON protocol is the priority, but several small changes also improve manual use:

- `help <command>` for syntax, examples, and argument descriptions;
- consistent tabular columns and a `--limit` option for list commands;
- command echo and sequence number in scripts;
- distinct `accepted`, `completed`, and `failed` wording;
- `why <task-or-action-id>` to show blockers;
- text shorthands for `observe`, `advance until`, and `assert`;
- completion hints based on catalogs in interactive mode.

These should be views over the same command descriptors and result objects as JSON mode, preventing documentation drift.

## Architecture

Refactor `GameCommandShell` in layers:

```text
stdin / script / Gradle properties
              |
      text or NDJSON codec
              |
     command registry + schemas
              |
       typed command handlers
              |
  player task/production/model seams
              |
 snapshot builder + event journal
              |
      typed result and codec
```

Key implementation choices:

- Introduce `CommandRequest`, `CommandResponse`, `CommandError`, and typed result records.
- Replace the large string-based dispatch switch incrementally with a command registry. Each descriptor owns names/aliases, schema, help, validation, and handler.
- Keep the current tokenizer as the text codec; translate parsed text into the same typed requests used by JSON.
- Build observations from immutable DTOs at a tick boundary. Never serialize model objects directly.
- Isolate JSON behind a small codec interface. If adding a JSON dependency is undesirable, first evaluate the JDK-compatible libraries already acceptable to the project; do not hand-roll escaping or parsing.
- Move lifecycle logs to standard error in JSON mode. Standard output must remain valid NDJSON even after failures.
- Keep all simulation calls on the game thread. Input readers may queue requests but may not inspect or mutate the world.

## Safety and modes

Expose three explicit capability modes:

- `play`: only actions available through normal player systems;
- `test`: play actions plus assertions, checkpoints, and additional diagnostics;
- `debug`: optional direct state mutation, clearly marked non-gameplay and disabled unless requested.

Responses include the active mode. Transcripts record any debug action, and state hashes after debug mutation must not be presented as a normal-play proof.

Resource limits should include maximum ticks per request, maximum response bytes, maximum spatial volume, page size, event history, and wall-clock timeout. Limit failures return a specific code and the permitted maximum.

## Delivery plan

### Phase 1: protocol foundation

- Add `--protocol=text|json`, request IDs, envelopes, and clean stdout/stderr separation.
- Add `capabilities`, structured errors, and JSON representations for `status`, `hash`, and existing command results.
- Golden-test framing, escaping, deterministic field ordering where snapshots are compared, and exit behavior.

This phase immediately makes basic automation reliable without changing game behavior.

### Phase 2: discovery and observation

- Add the command registry and `describe`.
- Implement typed, filtered `observe` sections and pagination.
- Define identity and lifecycle contracts.
- Convert existing inspection commands to render the shared observation DTOs.

### Phase 3: action lifecycle and conditional advance

- Add typed `act`, `client_tag`, dry-run validation, and action/task IDs.
- Add the event journal and blocker reason codes.
- Implement bounded `advance until` and return deltas.

This is the milestone at which an AI can efficiently play rather than merely issue scripts.

### Phase 4: testing workflow

- Add the safe condition language, `assert`, checkpoints, transcripts, and replay verification.
- Provide scenario fixtures demonstrating mining, construction, stockpiling, and the basic-village chain.
- Add performance and long-run bounds tests.

## Testing strategy

Protocol tests should verify behavior rather than formatted prose:

- every input line yields exactly one correlated response;
- malformed JSON does not corrupt the following request;
- unknown operations and invalid fields produce stable error codes;
- observations are deterministic for the same seed, actions, and tick count;
- pagination has no gaps or duplicates and cursors reject the wrong world epoch;
- delta application reconstructs the corresponding full snapshot;
- `advance` never exceeds its tick/time budget and reports one terminal reason;
- dry-run and submission share validation rules;
- actions travel through the normal model seam;
- stdout is valid NDJSON even when warnings or exceptions occur;
- replay produces the expected final state hash.

Retain end-to-end child-process tests in addition to in-JVM handler tests. The former catch stdout contamination, argument parsing, exit codes, hangs, buffering, and working-directory mistakes that unit tests cannot.

## Acceptance scenario

The first end-to-end AI-play acceptance test should require no hard-coded starting coordinates:

1. Start a normal map with seed 42 in JSON test mode.
2. Discover protocol capabilities and the schemas for observation, mining, stockpiles, and building.
3. Observe the settlement origin, citizens, nearby terrain/resources, and available catalogs.
4. Dry-run then submit a mining order using a `client_tag`.
5. Advance until that action completes, blocks, or reaches a fixed tick budget.
6. If blocked, inspect the structured reason and take a documented corrective action.
7. Create food storage and production orders through normal gameplay actions.
8. Advance until the precise `food_ready` predicate is true or the budget expires.
9. Assert the predicate and record final state/terrain hashes.
10. Replay the transcript and obtain the same terminal reason and hashes.

Passing this scenario demonstrates discoverability, grounding, planning feedback, bounded execution, outcome verification, and reproducibility: the essential properties of an AI-playable CLI.

## Success metrics

- An agent can complete the acceptance scenario using only `capabilities` and `describe`, without repository source access.
- No parsing of English messages is required in machine mode.
- A typical decision step fits in a small bounded response; full-world dumps are unnecessary.
- All waits have deterministic tick bounds and explicit terminal reasons.
- Replaying a committed scenario on the same version and platform determinism contract yields the same final hashes.
- Existing text scripts and commands continue to work throughout migration.

## Open questions

- Which model fields are safe and useful enough to make part of the stable observation contract?
- Do tasks and production queues already have durable IDs, or should the CLI allocate protocol-side IDs?
- Should revisions advance per simulation tick or only on observable changes? The latter is more compact but needs careful event integration.
- Which small JSON library best matches the project's dependency policy and Java 25 target?
- How much blocker diagnosis can be derived cheaply from current task/pathfinding state without changing simulation behavior?
- Which subset of conditions is sufficient for the first `advance` and `assert` implementation?

The recommended defaults are observable-change revisions, protocol-side IDs only where the model lacks stable IDs, and a deliberately small condition language expanded from real scenario needs.
