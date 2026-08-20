# OpenTowns Run Guide

From the repository root in PowerShell:

```powershell
.\gradlew run
```

This launches the normal windowed game. Gradle sets the working directory to `src/` and auto-provisions the required Java 25 toolchain.

Optional commands:

```powershell
.\gradlew runHeadless -Pseed=42 -Pticks=3000
```

This runs the deterministic headless simulation with a fixed seed.

The headless runner also has a command shell for manual testing. Commands are
executed in order and the normal automatic tick loop is skipped unless
`-Pticks` is supplied:

```powershell
.\gradlew runHeadless -Pseed=42 -Pcommands="status; origin; tick 1000; hash"
```

Use `commands` for the complete live catalog. Inspection commands are
`status`, `world`, `cell x y z`, `livings [id]`, `items [id]`, `tasks`,
`buildings`, `stockpiles`, `catalog items|terrain|buildings|actions|zones|livings`,
`automation [action-id]`, `progress`, `food-ready`, `origin`, and `hash`. Simulation commands are `tick [count]`, `step [count]`, `pause`,
`resume`, `view x y z`, `speed up|down`, `save name`, and `quit`.

`setup village [x y z|status|auto [cycles]]` (or the `village` shortcut) queues a compact starter
layout near the starting citizens: dining and prepared-food storage, a
40-tile apple/pear orchard, a larger wheat field, carpentry/masonry/bakery areas, the
workshop dependency chain for flour and bread, nearby gathering, and minimums
of 20 apples, 20 pears, 10 wheat, 10 flour, and 10 bread. See
`docs/basic-village.md` for the complete walkthrough.

For AI or other machine clients, use the versioned JSON-lines protocol. One
request is read from stdin and one JSON response is written to stdout per line;
diagnostic lifecycle output remains on stderr:

```powershell
@'
{"id":"c1","op":"capabilities"}
{"id":"o1","op":"observe"}
{"id":"a1","op":"assert","args":{"condition":{"path":"summary.citizens","op":"gte","value":1}}}
'@ | .\gradlew runHeadless -Pseed=42 -Pprotocol=json -Pticks=0
```

The initial protocol supports `capabilities`, `describe`, `observe`, `act`,
`advance`, and `assert`. See `docs/ai-cli-design.md` for the protocol and
planned extensions.

When using `-Pticks=N`, commands in `-Pcommands` run first. Use
`-PpostCommands="status; buildings; stockpiles"` to inspect the state after
those ticks.

Orders include the shortcuts `mine`, `mine-ladder`, `mine-area`, `dig`,
`cancel-order`, `build`, and `stockpile`. The generic form
`order task-name [parameter] [parameter2] [x y z [x2 y2 z2]]` exposes the
remaining player task types; `commands` lists the task names.

For a longer script, put one command per line in a file and use
`-Pscript=path\to\commands.txt`; use `-Pinteractive=true` to read commands
from standard input.

```powershell
.\gradlew test
```

This runs the JUnit test suite.

Notes:

- On first launch, the game may ask for a Towns install so it can copy `data/graphics`, `data/audio`, and `data/fonts`.
- Run commands from the repo root so relative paths resolve correctly.
