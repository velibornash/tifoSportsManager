# Which engine is live

One page, five facts. The most expensive recurring mistake in this project was not knowing which
match engine a thing belonged to — there have been three separate `MatchOrchestrator` and
`MatchState` classes, a dashboard button still called `start-realistic-demo-btn` for historical
reasons, and a viewer served from `static/demo/service/ui/proposal/` that looks like a demo. Every
hour lost to "which engine is this?" is what this file exists to prevent.

**If you are adding a match feature and you are not sure which of these classes to touch, you are
about to work on the wrong engine.** Everything else in this repository that simulates football is
quarantined, stubbed or deleted.

---

## The live path

| | |
|---|---|
| **Engine package** | `org.example.footballmanager.newLogic.sim` |
| **Entry point class** | `SimMatchService` (called by `SimulationController`) |
| **Entry point endpoint** | `POST /simulation/current-round/prepare` |
| **Dashboard button id** | `start-realistic-demo-btn` — labelled **"⚽ Watch Your Match"** |
| **Viewer path** | `/demo/service/ui/proposal/index.html?matchId=<replayId>` |

The button id is misnamed and the viewer lives under a `proposal/` directory. **Both are load-bearing
history, not mistakes to "clean up"** — renaming the id or the folder without finding every
reference produces a dead button and a 404, which is exactly the confusion this file documents.

---

## The chain, in order

```
dashboard.html
  └─ #start-realistic-demo-btn          "⚽ Watch Your Match"
       └─ startRealisticDemoTest()      static/js/demo.js
            └─ POST /simulation/current-round/prepare
                 └─ SimulationController.prepareCurrentRound()
                      └─ SimMatchService.simulate()      newLogic/sim/
                           └─ SimMatchRunner              one full 3600-tick match
                                └─ MatchOrchestrator      the tick loop
                                     └─ SimReplayStore   in-memory, AtomicLong ids
                                          └─ replay served from /api/sim/replay/{id}
                                               └─ viewer: static/demo/service/ui/proposal/
```

Persisted matches are written by `SimReportMapper`; the replay the viewer reads is a **downsampled**
view (`SimReplayView`, stride 10) served from `SimReplayController`, not the raw tick stream.

---

## Not the live path

Verified against the tree on 2026-09-28. If you are adding a match feature and you are not sure which
class to touch, you are about to work on the wrong engine.

| Location | What it was | State |
|---|---|---|
| `org.example.footballmanager.demo.service` | The first service-engine prototype — 97 Java files, its own `MatchSimulator`, rules, VAR and restart handling. Superseded by `newLogic.sim`. | **Still present, not live.** No live controller imports it. |
| `org.example.footballmanager.newLogic.old` | An earlier cut, itself already emptied. | Two empty package directories. |
| `/api/v2/match/*` | The `newLogic` root engine (`DecisionEngine`, `OffsideTracker`, `SetPieceHandler`). | **Deleted.** No references remain. |
| `engines/RealisticMatchEngine`, `/start-realistic-demo`, `cleanSheet/`, `old/`, `zox/` | The realtime and zox replay family. | **Deleted.** |
| `static/demo/service/ui/` | A second, older viewer beside the live `proposal/` one. | The `proposal/` viewer is the live one — see the table above. |

Sprint 6 removed 125 Java files, 8 orphaned frontend pages, 8 stub services and every empty
controller to make this list short enough to be true. If you find yourself adding a *fourth*
`MatchState`, stop — that is the mistake this page exists to stop.

`demo/service` is the one judgement call here. It is large, it is a complete working engine, and it
is not wired to anything a manager can reach. It was left rather than deleted because
`MatchBatchRunner` and `MatchChainTrace` are still useful as batch diagnostics, and deleting 97 files
is a change that deserves its own commit and its own decision.

## Why there was ever more than one

Worth knowing before deleting anything else, because the duplicates look like bugs and are not:

Three engines were each, in turn, "the clean engine": `newLogic`, `newLogic.sim` and `demo/service`.
`demo/service` was first to get a real service boundary but grew God classes. `newLogic` was the
rewrite that killed those classes and was itself superseded when `/api/v2/match` was found to need a
thin orchestrator rather than a fixed tick list. `newLogic.sim` is what survived, because it is the
only one where adding an engine means **adding a class** — each engine sits behind `EngineInterfaces`
and the orchestrator only sequences them — rather than editing a 2000-line `MatchSimulator`.

That is the test to apply to any new engine: if wiring it means adding a branch inside a god class,
it is the old pattern wearing a new package name.
