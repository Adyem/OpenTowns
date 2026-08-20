# Basic village setup

The headless CLI has a high-level starter plan so the first town does not
depend on guessed coordinates or a long fragile command list.

From the repository root, start a deterministic run and let the staged plan
discover the random starting area:

```powershell
.\gradlew runHeadless -Pseed=42 -Pcommands="setup village auto; progress" -Pticks=0 -PpostCommands="progress; zones; stockpiles; buildings"
```

`setup village` automatically uses the starting citizens' ground level, so it
works when world generation places the settlement somewhere different on the
next run. To choose another ground tile, give an explicit coordinate:

```text
setup village x y z
```

The coordinate is `x y z` for the floor where citizens stand. `origin` prints
the automatically selected coordinate for the current world. The plan keeps
the layout close to that point and queues:

- a 40-tile apple/pear orchard, with 20 apple tiles and 20 pear tiles;
- a larger wheat field; after the first tilling pass, rerunning setup places
  seeds on the tilled fields;
- a dining room and at least 40 usable `prepfood` cells for apples and pears;
- a separate `rawfood` storage area for wheat and flour, because those items
  are classified as raw food by the game data;
- carpentry, masonry, and bakery work areas plus a raw-materials store;
- carpentry bench, wood detailer, mason bench, mill, baker's table, and baker's
  oven, in dependency order;
- nearby apple, pear, and wild-wheat gathering plus ordinary tree chopping for
  the initial wood supply;
- automated minimums of 20 apples, 20 pears, 10 wheat, 10 flour, and 10 bread.

The command queues work; it does not instantly create finished buildings or
food. `auto` runs five bounded discovery stages, re-running setup between
them so newly exposed stone and tilled ground can be used. It advances about
210,000 ticks and may take a little while. For an interactive run, use
`setup village auto 1` or `auto 2`, then repeat `setup village` after more
ticks. `progress` reports both total items and what is currently in the
prepared/raw-food stores. Use `food-ready` to verify at least 20 apples and
20 pears are actually in prepared-food storage:

```powershell
.\gradlew runHeadless -Pseed=42 -Pcommands="setup village auto 2; progress; queues summary" -Pticks=60000 -PpostCommands="setup village; progress; zones; stockpiles; buildings"
```

For a live windowed game, use the same command after enabling the console:

```powershell
.\gradlew run -Pcli=true -PcliAutoStart=true -PskipLauncher=true
```

Then type `setup village auto`, `progress`, `queues summary`, `buildings`,
`stockpiles`, or `status`. The `automation` command remains available for
inspecting individual production rules without remembering their internals.

## Useful CLI habits

Use `commands` for the complete catalog and `help` for the compact summary.
Use one command per line in a script passed with `-Pscript=path\to\commands.txt`;
blank lines and lines beginning with `#` are ignored. In command mode, ticks
only advance when `tick [count]` is issued or when `-Pticks=N` is supplied.
Commands in `-Pcommands` run before the explicit tick count; commands in
`-PpostCommands` run after it.

Check the result of a setup with:

```text
origin
progress
food-ready
queues summary
zones
stockpiles
buildings
find item plantedappletree
find item plantedpeartree
find item plantedwheat
find item apple
find item pear
find item wheat
find item flour
find item bread
```

If a queue waits, inspect `progress`, `queues action-id`, `tasks`,
`buildings`, and the item searches. Stone access is intentionally staged: the
starter first mines a ladder path, places a ladder, then mines stone normally
so the usual `rmstone` drops are produced. The plan uses the normal task and
production systems rather than spawning resources.
