package xaos;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import xaos.cli.GameCommandShell;
import xaos.cli.GameCommandProtocol;
import xaos.main.Game;
import xaos.main.World;
import xaos.tiles.Cell;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.living.LivingEntity;
import xaos.utils.AStarQueue;
import xaos.utils.Utils;
import xaos.utils.UtilsSavegame;

/**
 * Headless test entry point: runs worldgen and the per-frame simulation
 * without a window, GL context, audio device, or any of the proprietary
 * graphics/audio/font assets. The windowed game (xaos.Towns) is untouched.
 *
 * With --seed the run is deterministic: the single game RNG (Utils.random)
 * is seeded and pathfinding runs synchronously on this thread instead of on
 * the A* worker. The same seed and tick count always produce the same world
 * state; different seeds produce different worlds. Without --seed it behaves
 * like the shipped game (async pathfinding, time-seeded RNG).
 *
 * One tick = one frame of the vanilla main loop (World.nextTurn call); the
 * turn cadence within is the game's own FRAMES_PER_TURN logic.
 *
 * Usage:
 *   xaos.TownsHeadless [--seed=N] [--ticks=N] [--map=normal|desert|jungle|mixed|snow|mountains] [--user-folder=path]
 *                      [--save=name] [--load=name] [--commands="setup village; tick 100"]
 *                      [--script=file] [--interactive] [--protocol=json] [--post-commands="status; buildings"]
 *
 * --save=name writes a savegame (user-folder/save/name.zip) after the tick
 * loop, right before the summary. --load=name skips worldgen and loads that
 * savegame instead, then runs the tick loop as usual; combined with
 * --ticks=0 it prints the hash of the loaded state, which lets tests assert
 * a save/load round-trip preserves world state across two processes.
 *
 * A windowed "New game" is campaign c1 with the map type as mission id;
 * --map picks the same thing (default normal). Works from the same working
 * directory as the game (src/): towns.ini, graphics.ini and data/ resolve
 * relative to it. The user folder defaults to a sandbox under java.io.tmpdir
 * so test runs never touch the player's real save folder.
 */
public final class TownsHeadless {

    private TownsHeadless() {
    }

    public static void main(String[] args) {
        Long seed = null;
        long ticks = 3000;
        boolean ticksSpecified = false;
        String map = "normal";
        String userFolderBase = System.getProperty("java.io.tmpdir") + System.getProperty("file.separator") + "opentowns-headless";
        String saveName = null;
        String loadName = null;
        List<String> commands = new ArrayList<String>();
        List<String> postCommands = new ArrayList<String>();
        String scriptName = null;
        boolean interactive = false;
        boolean jsonProtocol = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--seed=")) {
                seed = Long.valueOf(arg.substring("--seed=".length()));
            } else if (arg.startsWith("--ticks=")) {
                ticks = Long.parseLong(arg.substring("--ticks=".length()));
                if (ticks < 0) {
                    throw new IllegalArgumentException("--ticks must not be negative");
                }
                ticksSpecified = true;
            } else if (arg.startsWith("--map=")) {
                map = arg.substring("--map=".length());
            } else if (arg.startsWith("--user-folder=")) {
                userFolderBase = arg.substring("--user-folder=".length());
            } else if (arg.startsWith("--save=")) {
                saveName = arg.substring("--save=".length());
            } else if (arg.startsWith("--load=")) {
                loadName = arg.substring("--load=".length());
            } else if (arg.startsWith("--command=")) {
                commands.add(arg.substring("--command=".length()));
            } else if (arg.startsWith("--commands=")) {
                addCommands(commands, arg.substring("--commands=".length()));
            } else if (arg.startsWith("--post-commands=")) {
                addCommands(postCommands, arg.substring("--post-commands=".length()));
            } else if (arg.startsWith("--script=")) {
                scriptName = arg.substring("--script=".length());
            } else if (arg.equals("--interactive")) {
                interactive = true;
            } else if (arg.equals("--protocol=json")) {
                jsonProtocol = true;
                interactive = true;
            } else {
                System.err.println("Unknown argument: " + arg);
                System.err.println("Usage: xaos.TownsHeadless [--seed=N] [--ticks=N] [--map=type] [--user-folder=path] [--save=name] [--load=name] [--commands=...|--script=file|--interactive] [--protocol=json] [--post-commands=...]");
                System.exit(1);
            }
        }

        // Keep stdout parseable in machine mode. Startup and final summaries
        // continue to be useful diagnostics, but belong on stderr.
        PrintStream protocolOutput = System.out;
        if (jsonProtocol) {
            System.setOut(System.err);
        }

        File fBase = new File(userFolderBase);
        if (!fBase.exists()) {
            fBase.mkdirs();
        }

        Game.initHeadless(userFolderBase);

        if (seed != null) {
            // Deterministic test mode: synchronous pathfinding + seeded RNG.
            // The seed goes in right before worldgen so it fixes the map,
            // the population and every draw after.
            AStarQueue.setSynchronousMode(true);
            Utils.setRandomSeed(seed.longValue());
        }

        boolean commandMode = !commands.isEmpty() || scriptName != null || interactive || !postCommands.isEmpty();
        String tickDescription = commandMode && !ticksSpecified ? "command" : Long.toString(ticks);
        System.out.println("[TownsHeadless] seed=" + (seed != null ? seed.toString() : "none") + " ticks=" + tickDescription + " map=" + map);

        long lTime = System.currentTimeMillis();
        if (loadName != null) {
            // continueGame's missing-file branch goes through exitToMainMenu,
            // which touches windowed-only panels; check here instead.
            String sZip = loadName + ".zip";
            File fSave = new File(Game.getUserFolder() + Game.getFileSeparator() + Game.SAVE_FOLDER1 + Game.getFileSeparator() + sZip);
            if (!fSave.exists()) {
                System.err.println("[TownsHeadless] savegame not found: " + fSave.getAbsolutePath());
                System.exit(1);
            }
            Game.continueGame(sZip, null);
            System.out.println("[TownsHeadless] loaded " + sZip + " (" + (System.currentTimeMillis() - lTime) + "ms)");
        } else {
            Game.startGame("c1", map);
            System.out.println("[TownsHeadless] worldgen done (" + (System.currentTimeMillis() - lTime) + "ms)");
        }

        World world = Game.getWorld();

        if (commandMode) {
            GameCommandShell shell = new GameCommandShell(world);
            if (scriptName != null && !runScript(shell, Path.of(scriptName))) {
                Game.exit();
                System.exit(1);
            }
            for (String command : commands) {
                if (!runCommand(shell, command)) {
                    Game.exit();
                    System.exit(1);
                }
                if (shell.isExitRequested()) {
                    break;
                }
            }
            if (interactive && !shell.isExitRequested()) {
                if (jsonProtocol) {
                    runJsonInteractive(shell, protocolOutput);
                } else {
                    runInteractive(shell);
                }
            }
            if (ticksSpecified && !shell.isExitRequested()) {
                if (!runCommand(shell, "tick " + ticks)) {
                    Game.exit();
                    System.exit(1);
                }
            }
            if (!shell.isExitRequested()) {
                for (String command : postCommands) {
                    if (!runCommand(shell, command)) {
                        Game.exit();
                        System.exit(1);
                    }
                    if (shell.isExitRequested()) {
                        break;
                    }
                }
            }
            System.out.println("[TownsHeadless] command mode complete (" + shell.getTicksAdvanced() + " ticks advanced)");
        } else {
            lTime = System.currentTimeMillis();
            for (long i = 0; i < ticks; i++) {
                world.nextTurn();
                if (AStarQueue.isSynchronousMode()) {
                    AStarQueue.drainSynchronously();
                }
            }
            System.out.println("[TownsHeadless] " + ticks + " ticks done (" + (System.currentTimeMillis() - lTime) + "ms)");
        }

        if (saveName != null) {
            try {
                Game.setSavegameName(saveName);
                UtilsSavegame.save(true);
                System.out.println("[TownsHeadless] saved " + saveName + ".zip");
            } catch (Exception e) {
                System.err.println("[TownsHeadless] save failed: " + e);
                e.printStackTrace();
                System.exit(1);
            }
        }

        printSummary(world);

        // The A* worker (unseeded mode) is a non-daemon thread; exit cleanly.
        // UtilsGL/UtilsAL destroy are no-ops headless (never initialized).
        Game.exit();
    }

    private static boolean runCommand(GameCommandShell shell, String command) {
        GameCommandShell.CommandResult result = shell.execute(command);
        if (!result.getMessage().isEmpty()) {
            System.out.println("[TownsHeadless] " + result.getMessage());
        }
        if (!result.isSuccessful()) {
            System.err.println("[TownsHeadless] command error: " + command);
        }
        return result.isSuccessful();
    }

    private static boolean runScript(GameCommandShell shell, Path script) {
        try {
            for (String line : Files.readAllLines(script)) {
                if (!runCommand(shell, line)) {
                    return false;
                }
                if (shell.isExitRequested()) {
                    return true;
                }
            }
            return true;
        } catch (IOException e) {
            System.err.println("[TownsHeadless] could not read command script " + script + ": " + e.getMessage());
            return false;
        }
    }

    private static void runInteractive(GameCommandShell shell) {
        System.out.println("[TownsHeadless] interactive mode; type 'help' or 'quit'");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (!shell.isExitRequested() && (line = reader.readLine()) != null) {
                runCommand(shell, line);
            }
        } catch (IOException e) {
            System.err.println("[TownsHeadless] interactive input failed: " + e.getMessage());
        }
    }

    private static void runJsonInteractive(GameCommandShell shell, PrintStream output) {
        GameCommandProtocol protocol = new GameCommandProtocol(shell);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (!shell.isExitRequested() && (line = reader.readLine()) != null) {
                output.println(protocol.execute(line));
                output.flush();
            }
        } catch (IOException e) {
            // stdout must remain valid NDJSON; diagnostics are on stderr.
            System.err.println("[TownsHeadless] JSON input failed: " + e.getMessage());
        }
    }

    private static void addCommands(List<String> commands, String value) {
        for (String command : value.split(";")) {
            if (!command.trim().isEmpty()) {
                commands.add(command.trim());
            }
        }
    }

    /**
     * World-state summary for run-to-run comparison: a few human-readable
     * counters plus an order-sensitive hash over terrain, livings and items.
     * Two runs match iff every line matches.
     */
    private static void printSummary(World world) {
        int numLivings = World.getLivings(true).size() + World.getLivings(false).size();

        System.out.println("[TownsHeadless] date=" + world.getDate().getDay() + "/" + world.getDate().getMonth() + "/" + world.getDate().getYear()
                + " citizens=" + World.getCitizenIDs().size()
                + " livings=" + numLivings
                + " items=" + World.getItems().size()
                + " coins=" + world.getCoins());
        System.out.println("[TownsHeadless] terrain-hash=" + Long.toHexString(computeTerrainHash()));
        System.out.println("[TownsHeadless] state-hash=" + Long.toHexString(computeStateHash()));
    }

    /**
     * FNV-1a-style hash over the terrain of the current world: type, fluids
     * and mined state of every cell.
     *
     * The definition of this hash and computeStateHash is FROZEN: the golden
     * pins in the test suite record their values for fixed seeds, so any
     * change here (or in what worldgen/the sim feeds them) invalidates every
     * pin. Extend the hashed surface only deliberately, updating the pins in
     * the same commit.
     */
    public static long computeTerrainHash() {
        long hash = FNV_OFFSET;
        Cell[][][] cells = World.getCells();
        for (int x = 0; x < cells.length; x++) {
            for (int y = 0; y < cells[0].length; y++) {
                for (int z = 0; z < cells[0][0].length; z++) {
                    Cell cell = cells[x][y][z];
                    hash = mix(hash, cell.getTerrain().getTerrainID());
                    hash = mix(hash, cell.getTerrain().getFluidType());
                    hash = mix(hash, cell.getTerrain().getFluidCount());
                    hash = mix(hash, cell.isMined() ? 1 : 0);
                }
            }
        }
        return hash;
    }

    /**
     * Full world-state hash: the terrain hash continued over livings (id,
     * kind, position, health) and items (id, kind, position), in sorted-ID
     * order. Frozen; see computeTerrainHash.
     */
    public static long computeStateHash() {
        long hash = computeTerrainHash();

        // Livings: id, kind, position and health, in id order
        for (int d = 0; d < 2; d++) {
            Integer[] ids = World.getLivings(d == 0).keySet().toArray(new Integer[0]);
            java.util.Arrays.sort(ids);
            for (int i = 0; i < ids.length; i++) {
                LivingEntity le = World.getLivings(d == 0).get(ids[i]);
                hash = mix(hash, ids[i].intValue());
                hash = mix(hash, le.getIniHeader().hashCode());
                hash = mix(hash, le.getCoordinates().x);
                hash = mix(hash, le.getCoordinates().y);
                hash = mix(hash, le.getCoordinates().z);
                hash = mix(hash, le.getLivingEntityData().getHealthPoints());
            }
        }

        // Items: id, kind and position, in id order
        Integer[] itemIDs = World.getItems().keySet().toArray(new Integer[0]);
        java.util.Arrays.sort(itemIDs);
        for (int i = 0; i < itemIDs.length; i++) {
            Item item = World.getItems().get(itemIDs[i]);
            hash = mix(hash, itemIDs[i].intValue());
            hash = mix(hash, item.getIniHeader().hashCode());
            hash = mix(hash, item.getCoordinates().x);
            hash = mix(hash, item.getCoordinates().y);
            hash = mix(hash, item.getCoordinates().z);
        }

        return hash;
    }

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static long mix(long hash, int value) {
        hash ^= (value & 0xffffffffL);
        return hash * FNV_PRIME;
    }
}
