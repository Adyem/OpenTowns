package xaos.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import xaos.TownsHeadless;
import xaos.actions.Action;
import xaos.actions.ActionManager;
import xaos.actions.ActionManagerItem;
import xaos.main.Game;
import xaos.main.World;
import xaos.tiles.entities.buildings.BuildingManager;
import xaos.tiles.entities.buildings.BuildingManagerItem;
import xaos.tasks.Task;
import xaos.tasks.TaskManagerItem;
import xaos.stockpiles.Stockpile;
import xaos.tiles.Cell;
import xaos.tiles.entities.Entity;
import xaos.tiles.entities.buildings.Building;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.items.ItemManager;
import xaos.tiles.entities.items.ItemManagerItem;
import xaos.tiles.entities.living.Citizen;
import xaos.tiles.entities.living.LivingEntityManager;
import xaos.tiles.entities.living.LivingEntityManagerItem;
import xaos.tiles.terrain.TerrainManager;
import xaos.tiles.terrain.TerrainManagerItem;
import xaos.utils.AStarQueue;
import xaos.utils.Point3D;
import xaos.utils.Point3DShort;
import xaos.utils.UtilsSavegame;
import xaos.zones.Zone;
import xaos.zones.ZoneManager;
import xaos.zones.ZoneManagerItem;

/**
 * Small, presentation-free command interface for a running headless game.
 *
 * <p>The order commands deliberately use the same model seam as the windowed
 * UI. This makes the shell useful for manual probes and integration tests
 * without making the renderer or mouse state part of the test surface.</p>
 */
public final class GameCommandShell {

    private World world;
    private long ticksAdvanced;
    private boolean exitRequested;
    private Point3D cachedVillageOrigin;
    private Point3D cachedStoneColumn;
    private boolean villageSoilQueued;
    private boolean villageSeedsQueued;
    private int lastTaskId = -1;

    public GameCommandShell(World world) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        this.world = world;
    }

    /** Executes one line and returns a machine-readable result. */
    public CommandResult execute(String line) {
        if (line == null) {
            return failure("empty command");
        }

        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return success("");
        }

        List<String> words;
        try {
            words = tokenize(trimmed);
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
        if (words.isEmpty()) {
            return success("");
        }

        String command = words.get(0).toLowerCase();
        try {
            switch (command) {
                case "help":
                    require(words, 1, "help");
                    return success(helpText());
                case "commands":
                    require(words, 1, "commands");
                    return success(commandCatalog());
                case "status":
                case "summary":
                    require(words, 1, command);
                    return success(statusText());
                case "world":
                    require(words, 1, "world");
                    return success("size=" + World.getCells().length + "x" + World.getCells()[0].length + "x" + World.getCells()[0][0].length
                            + " " + statusText());
                case "origin":
                case "starting-point":
                    require(words, 1, command);
                    return origin();
                case "hash":
                    require(words, 1, "hash");
                    return success(hashText());
                case "cell":
                    return cell(words);
                case "livings":
                case "citizens":
                    return livings(words);
                case "items":
                    return items(words);
                case "find":
                    return find(words);
                case "tasks":
                    require(words, 1, "tasks");
                    return tasks();
                case "why":
                    return why(words);
                case "queues":
                case "actions-queued":
                    return queues(words);
                case "buildings":
                    require(words, 1, "buildings");
                    return buildings();
                case "zones":
                    require(words, 1, "zones");
                    return zones();
                case "stockpiles":
                    require(words, 1, "stockpiles");
                    return stockpiles();
                case "catalog":
                    return catalog(words);
                case "tick":
                case "step":
                    return tick(words);
                case "mine":
                    return orderArea(words, Task.TASK_MINE, null, "mine");
                case "mine-ladder":
                    return orderArea(words, Task.TASK_MINE_LADDER, null, "mine-ladder");
                case "dig":
                    return orderArea(words, Task.TASK_DIG, null, "dig");
                case "cancel-order":
                    return orderArea(words, Task.TASK_CANCEL_ORDER, null, "cancel-order");
                case "mine-area":
                    return orderArea(words, Task.TASK_MINE, null, "mine-area");
                case "build":
                    return build(words);
                case "stockpile":
                    return stockpile(words);
                case "order":
                    return genericOrder(words);
                case "save":
                    return save(words);
                case "automate":
                    return automate(words);
                case "automation":
                case "production":
                    return automation(words);
                case "setup":
                    return setup(words);
                case "village":
                    if (words.size() == 2 && words.get(1).equalsIgnoreCase("status")) {
                        return villageStatus();
                    }
                    if (words.size() >= 2 && words.get(1).equalsIgnoreCase("auto")) {
                        return setupVillageAuto(words, 1);
                    }
                    return setupVillage(words, 1);
                case "progress":
                case "village-status":
                    require(words, 1, command);
                    return villageStatus();
                case "food-ready":
                    require(words, 1, "food-ready");
                    return foodReady();
                case "pause":
                    require(words, 1, "pause");
                    Game.pause(false);
                    return success("paused=" + Game.isPaused());
                case "resume":
                    require(words, 1, "resume");
                    Game.resume(false);
                    return success("paused=" + Game.isPaused());
                case "toggle-pause":
                    require(words, 1, "toggle-pause");
                    Game.togglePause(false);
                    return success("paused=" + Game.isPaused());
                case "view":
                    return view(words);
                case "speed":
                    return speed(words);
                case "quit":
                case "exit":
                    require(words, 1, command);
                    exitRequested = true;
                    return success("exiting");
                default:
                    return failure("unknown command: " + words.get(0) + " (try 'help')");
            }
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        } catch (RuntimeException e) {
            return failure("command failed: " + e.getMessage());
        }
    }

    public boolean isExitRequested() {
        return exitRequested;
    }

    public World getWorld() {
        return world;
    }

    public long getTicksAdvanced() {
        return ticksAdvanced;
    }

    public void setTicksAdvanced(long ticks) {
        if (ticks < 0) throw new IllegalArgumentException("ticks must not be negative");
        ticksAdvanced = ticks;
    }

    public int getLastTaskId() {
        return lastTaskId;
    }

    /** Rebinds the shell after a headless checkpoint load. */
    public void reloadWorld(World replacement) {
        if (replacement == null) {
            throw new IllegalArgumentException("replacement world must not be null");
        }
        // A loaded world is a new simulation epoch; command-local caches must
        // not leak coordinates or setup state from the previous epoch.
        cachedVillageOrigin = null;
        cachedStoneColumn = null;
        villageSoilQueued = false;
        villageSeedsQueued = false;
        lastTaskId = -1;
        world = replacement;
        ticksAdvanced = 0;
        exitRequested = false;
    }

    private CommandResult tick(List<String> words) {
        if (words.size() > 2) {
            throw new IllegalArgumentException("usage: tick [count]");
        }
        long count = words.size() == 1 ? 1 : parseNonNegativeLong(words.get(1), "tick count");
        for (long i = 0; i < count; i++) {
            world.nextTurn();
            if (AStarQueue.isSynchronousMode()) {
                AStarQueue.drainSynchronously();
            }
        }
        ticksAdvanced += count;
        return success("advanced " + count + " tick" + (count == 1 ? "" : "s"));
    }

    private CommandResult orderArea(List<String> words, int taskType, String parameter, String name) {
        int expected = name.equals("mine-area") ? 7 : 4;
        if (words.size() != expected) {
            throw new IllegalArgumentException("usage: " + name + (name.equals("mine-area")
                    ? " x1 y1 z1 x2 y2 z2" : " x y z"));
        }
        Point3D from = point(words, 1);
        Point3D to = name.equals("mine-area") ? point(words, 4) : from;
        Task task = issue(taskType, parameter, null, from, to);
        return task == null ? failure(name + " order was rejected")
                : success(name + " order accepted");
    }

    private CommandResult build(List<String> words) {
        if (words.size() != 5) {
            throw new IllegalArgumentException("usage: build building-id x y z");
        }
        Point3D point = point(words, 2);
        Task task = issue(Task.TASK_BUILD, words.get(1), null, point, point);
        return task == null ? failure("build order was rejected") : success("build order accepted");
    }

    private CommandResult stockpile(List<String> words) {
        if (words.size() != 8) {
            throw new IllegalArgumentException("usage: stockpile kind x1 y1 z1 x2 y2 z2");
        }
        Point3D from = point(words, 2);
        Point3D to = point(words, 5);
        Task task = issue(Task.TASK_STOCKPILE, words.get(1), null, from, to);
        return task == null ? failure("stockpile order was rejected") : success("stockpile order accepted");
    }

    /** Issues any task type that the simulation itself can represent. */
    private CommandResult genericOrder(List<String> words) {
        if (words.size() < 2) {
            throw new IllegalArgumentException("usage: order task-name [parameter] [parameter2] [x y z [x2 y2 z2]]");
        }
        int taskType = taskType(words.get(1));
        int remaining = words.size() - 2;
        int parameterCount;
        int pointCount;
        if (remaining <= 2) {
            parameterCount = remaining;
            pointCount = 0;
        } else if (remaining == 3 || remaining == 6) {
            parameterCount = 0;
            pointCount = remaining;
        } else if (remaining == 4 || remaining == 7) {
            parameterCount = 1;
            pointCount = remaining - 1;
        } else if (remaining == 5 || remaining == 8) {
            parameterCount = 2;
            pointCount = remaining - 2;
        } else {
            throw new IllegalArgumentException("usage: order task-name [parameter] [parameter2] [x y z [x2 y2 z2]]");
        }
        String parameter = parameterCount > 0 ? words.get(2) : null;
        String parameter2 = parameterCount > 1 ? words.get(3) : null;
        int pointOffset = 2 + parameterCount;
        Point3D from = pointCount == 0 ? null : point(words, pointOffset);
        Point3D to = pointCount == 6 ? point(words, pointOffset + 3) : from;
        Task task = issue(taskType, parameter, parameter2, from, to);
        return task == null ? failure("order was rejected") : success("order accepted task=" + words.get(1));
    }

    private CommandResult cell(List<String> words) {
        if (words.size() != 4) {
            throw new IllegalArgumentException("usage: cell x y z");
        }
        Point3D point = point(words, 1);
        validatePoint(point);
        Cell cell = World.getCell(point.x, point.y, point.z);
        StringBuilder result = new StringBuilder();
        result.append("cell=").append(point.x).append(',').append(point.y).append(',').append(point.z)
                .append(" terrain=").append(cell.getTerrain().getTerrainID())
                .append(" fluidType=").append(cell.getTerrain().getFluidType())
                .append(" fluidCount=").append(cell.getTerrain().getFluidCount())
                .append(" mined=").append(cell.isMined())
                .append(" discovered=").append(cell.isDiscovered())
                .append(" astar=").append(cell.getAstarZoneID())
                .append(" empty=").append(cell.isEmpty())
                .append(" orders=").append(cell.isFlagOrders())
                .append(" patrol=").append(cell.isFlagPatrol());
        if (cell.getEntity() != null) {
            result.append(" entity=").append(entityText(cell.getEntity()));
        }
        if (cell.getItem() != null) {
            result.append(" item=").append(entityText(cell.getItem()));
        }
        return success(result.toString());
    }

    private CommandResult livings(List<String> words) {
        if (words.size() > 2) {
            throw new IllegalArgumentException("usage: livings [id]");
        }
        StringBuilder result = new StringBuilder();
        if (words.size() == 2) {
            int id = parseInt(words.get(1), "living id");
            xaos.tiles.entities.living.LivingEntity living = World.getLivingEntityByID(id);
            if (living == null) {
                return failure("living not found: " + id);
            }
            appendLiving(result, living);
        } else {
            for (Integer id : World.getLivings(true).keySet()) {
                appendLiving(result, World.getLivings(true).get(id));
            }
            for (Integer id : World.getLivings(false).keySet()) {
                appendLiving(result, World.getLivings(false).get(id));
            }
        }
        return success(result.length() == 0 ? "no livings" : result.toString().trim());
    }

    private CommandResult items(List<String> words) {
        if (words.size() > 2) {
            throw new IllegalArgumentException("usage: items [id]");
        }
        StringBuilder result = new StringBuilder();
        if (words.size() == 2) {
            int id = parseInt(words.get(1), "item id");
            Item item = World.getItems().get(id);
            if (item == null) {
                return failure("item not found: " + id);
            }
            appendItem(result, item);
        } else {
            Integer[] ids = World.getItems().keySet().toArray(new Integer[0]);
            java.util.Arrays.sort(ids);
            for (Integer id : ids) {
                appendItem(result, World.getItems().get(id));
            }
        }
        return success(result.length() == 0 ? "no items" : result.toString().trim());
    }

    private CommandResult find(List<String> words) {
        if (words.size() != 3 || !words.get(1).equalsIgnoreCase("item")) {
            throw new IllegalArgumentException("usage: find item type");
        }
        String wanted = words.get(2);
        StringBuilder result = new StringBuilder();
        Integer[] ids = World.getItems().keySet().toArray(new Integer[0]);
        java.util.Arrays.sort(ids);
        for (Integer id : ids) {
            Item item = World.getItems().get(id);
            if (item != null && item.getIniHeader().equalsIgnoreCase(wanted)) {
                appendItem(result, item);
            }
        }
        return success(result.length() == 0 ? "no matching items type=" + wanted : result.toString().trim());
    }

    private CommandResult tasks() {
        StringBuilder result = new StringBuilder();
        appendTasks(result, world.getTaskManager().getTaskItems());
        appendTasks(result, world.getTaskManager().getTaskItemsTemp());
        return success(result.length() == 0 ? "no tasks" : result.toString().trim());
    }

    private CommandResult why(List<String> words) {
        if (words.size() != 2) throw new IllegalArgumentException("usage: why task-or-action-id");
        String requested = words.get(1);
        String taskText = tasks().getMessage();
        for (String line : taskText.split("\\n")) {
            if (line.contains("id=" + requested + " ")) {
                return success("id=" + requested + " state=pending blocker=unknown detail=" + line);
            }
        }
        String queueText = queues(List.of("queues", requested)).getMessage();
        if (!queueText.equals("no queued actions")) return success("id=" + requested + " state=queued blocker=unknown detail=" + queueText);
        return failure("no task or action found: " + requested);
    }

    private CommandResult queues(List<String> words) {
        if (words.size() > 2) {
            throw new IllegalArgumentException("usage: queues [action-id|summary]");
        }
        String filter = words.size() == 2 && !words.get(1).equalsIgnoreCase("summary") ? words.get(1) : null;
        if (filter == null) {
            return queueSummary();
        }
        StringBuilder result = new StringBuilder();
        appendQueues(result, "active", world.getTaskManager().getCustomActions(), filter);
        appendQueues(result, "temporary", world.getTaskManager().getCustomActionsTemp(), filter);
        appendQueues(result, "waiting", world.getTaskManager().getCustomActionsWait(), filter);
        return success(result.length() == 0 ? "no queued actions" : result.toString().trim());
    }

    private CommandResult queueSummary() {
        Map<String, int[]> counts = new TreeMap<String, int[]>();
        addQueueCounts(counts, world.getTaskManager().getCustomActions(), 0);
        addQueueCounts(counts, world.getTaskManager().getCustomActionsTemp(), 1);
        addQueueCounts(counts, world.getTaskManager().getCustomActionsWait(), 2);
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            int[] values = entry.getValue();
            result.append("queue action=").append(entry.getKey())
                    .append(" active=").append(values[0])
                    .append(" temporary=").append(values[1])
                    .append(" waiting=").append(values[2]).append('\n');
        }
        return success(result.length() == 0 ? "no queued actions" : result.toString().trim());
    }

    private void addQueueCounts(Map<String, int[]> counts, List<Action> actions, int state) {
        for (Action action : actions) {
            int[] values = counts.get(action.getId());
            if (values == null) {
                values = new int[3];
                counts.put(action.getId(), values);
            }
            values[state]++;
        }
    }

    private CommandResult buildings() {
        StringBuilder result = new StringBuilder();
        for (Building building : World.getBuildings()) {
            result.append("building id=").append(building.getID())
                    .append(" type=").append(building.getIniHeader())
                    .append(" pos=").append(building.getCoordinates()).append('\n');
        }
        return success(result.length() == 0 ? "no buildings" : result.toString().trim());
    }

    private CommandResult zones() {
        StringBuilder result = new StringBuilder();
        for (Zone zone : world.getZones()) {
            result.append("zone id=").append(zone.getID())
                    .append(" type=").append(zone.getIniHeader())
                    .append(" points=").append(zone.getPoints().size())
                    .append(" operative=").append(zone.isOperative()).append('\n');
        }
        return success(result.length() == 0 ? "no zones" : result.toString().trim());
    }

    private CommandResult stockpiles() {
        StringBuilder result = new StringBuilder();
        for (Stockpile stockpile : world.getStockpiles()) {
            result.append("stockpile id=").append(stockpile.getID())
                    .append(" type=").append(stockpile.getType().getID())
                    .append(" points=").append(stockpile.getPoints().size())
                    .append(" filled=").append(stockpile.getFilledPoints()).append('\n');
        }
        return success(result.length() == 0 ? "no stockpiles" : result.toString().trim());
    }

    private CommandResult catalog(List<String> words) {
        if (words.size() != 2) {
            throw new IllegalArgumentException("usage: catalog items|terrain|buildings|actions|zones|livings");
        }
        StringBuilder result = new StringBuilder();
        if (words.get(1).equalsIgnoreCase("items")) {
            for (ItemManagerItem item : sortedValues(ItemManager.getAllItems())) {
                result.append("item type=").append(item.getIniHeader())
                        .append(" name=").append(item.getName()).append('\n');
            }
        } else if (words.get(1).equalsIgnoreCase("terrain")) {
            for (TerrainManagerItem terrain : sortedValues(TerrainManager.getTerrainList())) {
                result.append("terrain id=").append(terrain.getTerrainID())
                        .append(" type=").append(terrain.getIniHeader())
                        .append(" name=").append(terrain.getName())
                        .append(" mineTurns=").append(terrain.getMineTurns()).append('\n');
            }
        } else if (words.get(1).equalsIgnoreCase("buildings")) {
            for (BuildingManagerItem building : sortedValues(BuildingManager.getAllItems())) {
                result.append("building type=").append(building.getIniHeader())
                        .append(" name=").append(building.getName()).append('\n');
            }
        } else if (words.get(1).equalsIgnoreCase("actions")) {
            for (ActionManagerItem action : sortedValues(ActionManager.getAllItems())) {
                result.append("action id=").append(action.getId())
                        .append(" name=").append(action.getName()).append('\n');
            }
        } else if (words.get(1).equalsIgnoreCase("zones")) {
            for (ZoneManagerItem zone : sortedValues(ZoneManager.getAllItems())) {
                result.append("zone type=").append(zone.getIniHeader())
                        .append(" name=").append(zone.getName())
                        .append(" kind=").append(zone.getType()).append('\n');
            }
        } else if (words.get(1).equalsIgnoreCase("livings")) {
            for (LivingEntityManagerItem living : sortedValues(LivingEntityManager.getAllItems())) {
                result.append("living type=").append(living.getIniHeader())
                        .append(" name=").append(living.getName()).append('\n');
            }
        } else {
            throw new IllegalArgumentException("usage: catalog items|terrain|buildings|actions|zones|livings");
        }
        return success(result.length() == 0 ? "catalog is empty" : result.toString().trim());
    }

    private CommandResult view(List<String> words) {
        if (words.size() != 4) {
            throw new IllegalArgumentException("usage: view x y z");
        }
        Point3D point = point(words, 1);
        validatePoint(point);
        world.setView(point.x, point.y, point.z);
        return success("view=" + world.getView());
    }

    private CommandResult speed(List<String> words) {
        if (words.size() != 2 || !(words.get(1).equalsIgnoreCase("up") || words.get(1).equalsIgnoreCase("down"))) {
            throw new IllegalArgumentException("usage: speed up|down");
        }
        if (words.get(1).equalsIgnoreCase("up")) {
            World.addTurnsPerSecond();
        } else {
            World.removeTurnsPerSecond();
        }
        return success("speed=" + World.SPEED);
    }

    private void appendLiving(StringBuilder result, xaos.tiles.entities.living.LivingEntity living) {
        result.append("living id=").append(living.getID())
                .append(" type=").append(living.getIniHeader())
                .append(" pos=").append(living.getCoordinates())
                .append(" hp=").append(living.getLivingEntityData().getHealthPoints());
        if (living instanceof Citizen) {
            Task task = ((Citizen) living).getCurrentTask();
            result.append(" task=").append(task == null ? "none" : task.getTask());
        }
        result.append('\n');
    }

    private static void appendItem(StringBuilder result, Item item) {
        result.append("item id=").append(item.getID())
                .append(" type=").append(item.getIniHeader())
                .append(" pos=").append(item.getCoordinates())
                .append(" locked=").append(item.isLocked()).append('\n');
    }

    private static void appendTasks(StringBuilder result, List<TaskManagerItem> taskItems) {
        for (TaskManagerItem item : taskItems) {
            Task task = item.getTask();
            result.append("task id=").append(task.getID())
                    .append(" type=").append(task.getTask())
                    .append(" parameter=").append(task.getParameter())
                    .append(" from=").append(task.getPointIni())
                    .append(" to=").append(task.getPointEnd())
                    .append(" citizens=").append(item.getListCitizens()).append('\n');
        }
    }

    private static void appendQueues(StringBuilder result, String state, List<Action> actions, String filter) {
        for (Action action : actions) {
            if (filter != null && !filter.equalsIgnoreCase(action.getId())) {
                continue;
            }
            result.append("queue state=").append(state)
                    .append(" action=").append(action.getId())
                    .append(" entity=").append(action.getEntityID())
                    .append(" destination=").append(action.getDestinationPoint())
                    .append(" steps=").append(action.getQueue() == null ? 0 : action.getQueue().size())
                    .append('\n');
        }
    }

    private static String entityText(Entity entity) {
        return entity.getIniHeader() + "#" + entity.getID() + "@" + entity.getCoordinates();
    }

    private CommandResult save(List<String> words) {
        if (words.size() != 2 || words.get(1).isEmpty()) {
            throw new IllegalArgumentException("usage: save name");
        }
        try {
            Game.setSavegameName(words.get(1));
            UtilsSavegame.save(true);
            return success("saved " + words.get(1) + ".zip");
        } catch (Exception e) {
            return failure("save failed: " + e.getMessage());
        }
    }

    private CommandResult automate(List<String> words) {
        if (words.size() != 3) {
            throw new IllegalArgumentException("usage: automate action-id minimum");
        }
        String actionID = words.get(1);
        if (ActionManager.getItem(actionID) == null) {
            return failure("unknown action: " + actionID);
        }
        int target = parseInt(words.get(2), "minimum");
        if (target < 0) {
            throw new IllegalArgumentException("minimum must be non-negative");
        }
        int current = world.getTaskManager().getNumItemsOnAutomatedQueue(actionID);
        while (current < target) {
            world.getTaskManager().addItemOnAutomatedQueue(actionID);
            current++;
        }
        while (current > target) {
            world.getTaskManager().removeItemOnAutomatedQueue(actionID);
            current--;
        }
        return success("automated action=" + actionID + " minimum=" + target);
    }

    private CommandResult automation(List<String> words) {
        if (words.size() > 2) {
            throw new IllegalArgumentException("usage: automation [action-id]");
        }
        String requested = words.size() == 2 ? words.get(1) : null;
        StringBuilder result = new StringBuilder();
        for (ActionManagerItem action : sortedValues(ActionManager.getAllItems())) {
            if (requested == null || action.getId().equalsIgnoreCase(requested)) {
                result.append("automation action=").append(action.getId())
                        .append(" minimum=").append(world.getTaskManager().getNumItemsOnAutomatedQueue(action.getId()))
                        .append(" generated=").append(action.getGeneratedItem()).append('\n');
            }
        }
        if (requested != null && result.length() == 0) {
            return failure("unknown action: " + requested);
        }
        return success(result.length() == 0 ? "no automation rules" : result.toString().trim());
    }

    private CommandResult origin() {
        Point3D point = villageOrigin();
        return success("origin=" + point.x + " " + point.y + " " + point.z
                + " (ground/build level; derived from the starting citizens)");
    }

    private CommandResult setup(List<String> words) {
        if (words.size() < 2 || !words.get(1).equalsIgnoreCase("village")) {
            throw new IllegalArgumentException("usage: setup village [x y z]");
        }
        if (words.size() == 3 && words.get(2).equalsIgnoreCase("status")) {
            return villageStatus();
        }
        if (words.size() >= 3 && words.get(2).equalsIgnoreCase("auto")) {
            return setupVillageAuto(words, 2);
        }
        return setupVillage(words, 2);
    }

    /**
     * Queues a small, deliberately compact starter village. Coordinates are
     * relative to the starting citizens when omitted, so examples do not need
     * to guess the generated map's ground level.
     */
    private CommandResult setupVillage(List<String> words, int coordinateOffset) {
        int coordinateCount = words.size() - coordinateOffset;
        if (coordinateCount != 0 && coordinateCount != 3) {
            throw new IllegalArgumentException("usage: setup village [x y z]");
        }

        Point3D anchor = coordinateCount == 0
                ? villageOrigin()
                : point(words, coordinateOffset);
        validatePoint(anchor);

        // Keep the starter footprint compact and validate every derived point;
        // the origin is the random starting citizen rather than a map constant.
        Point3D diningFrom = offset(anchor, -8, -1);
        Point3D diningTo = offset(anchor, -5, 2);
        // Keep enough prepared-food cells for 20 apples and 20 pears. Wheat
        // and flour are rawfood in the data model, so give them their own
        // adjacent store instead of silently putting them in rawmaterials.
        Point3D foodFrom = offset(anchor, -8, 3);
        Point3D foodTo = offset(anchor, -4, 10);
        Point3D foodOverflowFrom = offset(anchor, -3, 7);
        Point3D foodOverflowTo = offset(anchor, 0, 10);
        Point3D rawFoodFrom = offset(anchor, 1, 7);
        Point3D rawFoodTo = offset(anchor, 4, 10);
        Point3D rawFoodOverflowFrom = offset(anchor, 1, -1);
        Point3D rawFoodOverflowTo = offset(anchor, 4, 2);
        Point3D rawFrom = offset(anchor, 5, 7);
        Point3D rawTo = offset(anchor, 8, 10);
        Point3D carpentryFrom = offset(anchor, -3, 3);
        Point3D carpentryTo = offset(anchor, 0, 6);
        Point3D masonryFrom = offset(anchor, 1, 3);
        Point3D masonryTo = offset(anchor, 4, 6);
        Point3D bakeryFrom = offset(anchor, 5, 3);
        Point3D bakeryTo = offset(anchor, 8, 6);
        // Keep 20 cells for each fruit, with a larger wheat patch underneath.
        // Crop points are bounded because random citizens can start close to a
        // map edge even though the compact building layout remains strict.
        Point3D orchardAppleFrom = bounded(offset(anchor, -10, -10));
        Point3D orchardAppleTo = bounded(offset(anchor, 9, -10));
        Point3D orchardPearFrom = bounded(offset(anchor, -10, -9));
        Point3D orchardPearTo = bounded(offset(anchor, 9, -9));
        Point3D wheatFrom = bounded(offset(anchor, -8, -6));
        Point3D wheatTo = bounded(offset(anchor, 7, -4));

        Point3D[] points = new Point3D[] {diningFrom, diningTo, foodFrom, foodTo, foodOverflowFrom, foodOverflowTo,
                rawFoodFrom, rawFoodTo, rawFoodOverflowFrom, rawFoodOverflowTo,
                rawFrom, rawTo,
                carpentryFrom, carpentryTo, masonryFrom, masonryTo, bakeryFrom, bakeryTo,
                orchardAppleFrom, orchardAppleTo, orchardPearFrom, orchardPearTo, wheatFrom, wheatTo};
        for (int i = 0; i < points.length; i++) {
            validatePoint(points[i]);
        }

        int accepted = 0;
        // A random start can be surrounded by undiscovered rock. Queue the
        // safe starter clearing first; after it completes, rerunning this
        // command materializes the zones and stockpiles in that clearing.
        Point3D clearingFrom = offset(anchor, -8, -8);
        Point3D clearingTo = offset(anchor, 8, 10);
        accepted += issueAndCount(Task.TASK_MINE, null, clearingFrom, clearingTo);
        // The starter map commonly has a shallow open pocket before the first
        // stone layer. Advance one safe mining step per setup pass so the
        // normal task system can discover and connect the next layer.
        accepted += queueStoneAccess(anchor);
        accepted += issueAndCount(Task.TASK_CREATE_ZONE, "zdining", diningFrom, diningTo);
        accepted += queueOrExpandStockpile("prepfood", foodFrom, foodTo);
        accepted += queueOrExpandStockpile("prepfood", foodOverflowFrom, foodOverflowTo);
        accepted += queueOrExpandStockpile("rawfood", rawFoodFrom, rawFoodTo);
        accepted += queueOrExpandStockpile("rawfood", rawFoodOverflowFrom, rawFoodOverflowTo);
        accepted += queueOrExpandStockpile("rawmaterials", rawFrom, rawTo);
        accepted += issueAndCount(Task.TASK_CREATE_ZONE, "zcarpentry", carpentryFrom, carpentryTo);
        accepted += issueAndCount(Task.TASK_CREATE_ZONE, "zmasonry", masonryFrom, masonryTo);
        accepted += issueAndCount(Task.TASK_CREATE_ZONE, "zbakery", bakeryFrom, bakeryTo);

        // Soil and planting are deliberately separate setup passes. This keeps
        // the queue from trying to place trees before the tilled cells exist.
        boolean placeCropsThisPass = villageSoilQueued;
        if (!villageSoilQueued) {
            accepted += issueAndCount(Task.TASK_CUSTOM_ACTION, "qtill", orchardAppleFrom, orchardAppleTo);
            accepted += issueAndCount(Task.TASK_CUSTOM_ACTION, "qtill", orchardPearFrom, orchardPearTo);
            accepted += issueAndCount(Task.TASK_CUSTOM_ACTION, "qtill", wheatFrom, wheatTo);
            villageSoilQueued = true;
        }
        if (placeCropsThisPass && !villageSeedsQueued) {
            // Queue seeds to be created and placed only after the till pass.
            accepted += issueAndCount(Task.TASK_QUEUE_AND_PLACE_AREA, "qappleseed", orchardAppleFrom, orchardAppleTo);
            accepted += issueAndCount(Task.TASK_QUEUE_AND_PLACE_AREA, "qpearseed", orchardPearFrom, orchardPearTo);
            accepted += issueAndCount(Task.TASK_QUEUE_AND_PLACE_AREA, "qwheatseed", wheatFrom, wheatTo);
            villageSeedsQueued = true;
        }

        // Gather nearby fruit and wheat, then queue every workshop dependency.
        // The starting settlement can be close to a map edge. Clamp only the
        // broad gathering search; the compact village layout remains strict so
        // a typo in an explicit anchor is reported instead of being surprising.
        Point3D gatherFrom = bounded(offset(anchor, -32, -32));
        Point3D gatherTo = bounded(offset(anchor, 32, 32));
        accepted += issueAndCount(Task.TASK_CUSTOM_ACTION, "qharvestapple", gatherFrom, gatherTo);
        accepted += issueAndCount(Task.TASK_CUSTOM_ACTION, "qharvestpear", gatherFrom, gatherTo);
        accepted += issueAndCount(Task.TASK_CUSTOM_ACTION, "qharvestwildwheat", gatherFrom, gatherTo);
        accepted += issueAndCount(Task.TASK_CUSTOM_ACTION, "qchop", gatherFrom, gatherTo);

        Set<String> workshopSpots = new HashSet<String>();
        Point3D carpentryBench = availableWorkshopSpot(carpentryFrom, carpentryTo, workshopSpots, offset(anchor, -1, 5));
        Point3D woodDetailer = availableWorkshopSpot(carpentryFrom, carpentryTo, workshopSpots, offset(anchor, 0, 5));
        Point3D masonBench = availableWorkshopSpot(masonryFrom, masonryTo, workshopSpots, offset(anchor, 2, 5));
        Point3D mill = availableWorkshopSpot(masonryFrom, masonryTo, workshopSpots, offset(anchor, 3, 5));
        Point3D bakerTable = availableWorkshopSpot(bakeryFrom, bakeryTo, workshopSpots, offset(anchor, 6, 5));
        Point3D bakerOven = availableWorkshopSpot(bakeryFrom, bakeryTo, workshopSpots, offset(anchor, 7, 5));
        accepted += queueWorkshop("qcarpentrybench", carpentryFrom, carpentryTo, carpentryBench);
        accepted += queueWorkshop("qwooddetailer", carpentryFrom, carpentryTo, woodDetailer);
        accepted += queueWorkshop("qmasonbench", masonryFrom, masonryTo, masonBench);
        accepted += queueWorkshop("qmill", masonryFrom, masonryTo, mill);
        accepted += queueWorkshop("qbakertable", bakeryFrom, bakeryTo, bakerTable);
        accepted += queueWorkshop("qbakeroven", bakeryFrom, bakeryTo, bakerOven);

        // Production minimums are maintained by the normal automated queue.
        accepted += automateAction("qharvestapple", 20);
        accepted += automateAction("qharvestpapple", 20);
        accepted += automateAction("qharvestpear", 20);
        accepted += automateAction("qharvestppear", 20);
        accepted += automateAction("qharvestwildwheat", 10);
        accepted += automateAction("qharvestwheat", 10);
        accepted += automateAction("qflour", 10);
        accepted += automateAction("qbread", 10);

        world.setView(anchor.x, anchor.y, anchor.z);
        return success("village setup queued at " + anchor.x + " " + anchor.y + " " + anchor.z
                + "; accepted=" + accepted + "; orchard=40 tiles; wheat=48 tiles; soil="
                + (villageSoilQueued ? "queued" : "pending") + "; trees="
                + (villageSeedsQueued ? "queued" : "after next setup pass")
                + "; storage=prepfood(apple:20 pear:20 bread:10),rawfood(wheat:10 flour:10)");
    }

    /**
     * Runs the known discovery stages for a new random start. The final setup
     * pass is intentional: it queues the workshop chain after stone becomes
     * available. A smaller cycle count is useful when running interactively.
     */
    private CommandResult setupVillageAuto(List<String> words, int coordinateOffset) {
        if (words.size() > coordinateOffset + 2
                || (words.size() == coordinateOffset + 2 && !isInteger(words.get(coordinateOffset + 1)))) {
            throw new IllegalArgumentException("usage: setup village auto [cycles 1-5]");
        }
        int cycles = words.size() == coordinateOffset + 2
                ? parseInt(words.get(coordinateOffset + 1), "cycles") : 5;
        if (cycles < 1 || cycles > 5) {
            throw new IllegalArgumentException("cycles must be between 1 and 5");
        }
        int[] stageTicks = new int[] {10000, 20000, 60000, 60000, 60000};
        List<String> base = Collections.singletonList("village");
        for (int i = 0; i < cycles; i++) {
            CommandResult setupResult = setupVillage(base, 1);
            if (!setupResult.isSuccessful()) {
                return setupResult;
            }
            tick(List.of("tick", Integer.toString(stageTicks[i])));
        }
        CommandResult finalSetup = setupVillage(base, 1);
        if (!finalSetup.isSuccessful()) {
            return finalSetup;
        }
        return success("village auto stages complete; advanced=" + ticksAdvanced
                + " ticks; run 'progress' and continue with 'setup village' after resources arrive");
    }

    private int issueAndCount(int taskType, String parameter, Point3D from, Point3D to) {
        return issue(taskType, parameter, null, from, to) == null ? 0 : 1;
    }

    /**
     * Stockpile orders are rectangles, but repeated setup passes can reveal
     * the same rectangle in pieces. Reuse the existing type and add only
     * newly available cells so setup remains safe to repeat.
     */
    private int queueOrExpandStockpile(String type, Point3D from, Point3D to) {
        Stockpile existing = null;
        for (Stockpile stockpile : world.getStockpiles()) {
            if (stockpile.getType() != null && type.equalsIgnoreCase(stockpile.getType().getID())) {
                existing = stockpile;
                break;
            }
        }

        if (existing == null) {
            return issueAndCount(Task.TASK_STOCKPILE, type, from, to);
        }

        int added = 0;
        for (int x = from.x; x <= to.x; x++) {
            for (int y = from.y; y <= to.y; y++) {
                if (Stockpile.isCellAvailableForStockpile(x, y, from.z)) {
                    Point3DShort point = Point3DShort.getPoolInstance(x, y, from.z);
                    existing.addPoint(point);
                    World.getCell(point).setStockPileID(existing.getID());
                    added++;
                }
            }
        }
        return added == 0 ? 0 : 1;
    }

    private int queueWorkshop(String actionID, Point3D areaFrom, Point3D areaTo, Point3D destination) {
        ActionManagerItem action = ActionManager.getItem(actionID);
        String generated = action == null ? null : action.getGeneratedItem();
        if (generated != null && hasItemInArea(generated, areaFrom, areaTo)) {
            return 0;
        }
        if (hasQueuedAction(actionID)) {
            return 0;
        }
        return issueAndCount(Task.TASK_QUEUE_AND_PLACE, actionID, destination, destination);
    }

    private Point3D availableWorkshopSpot(Point3D from, Point3D to, Set<String> reserved, Point3D fallback) {
        for (int y = from.y; y <= to.y; y++) {
            for (int x = from.x; x <= to.x; x++) {
                Point3D candidate = new Point3D(x, y, from.z);
                String key = x + "," + y + "," + from.z;
                Cell cell = World.getCell(candidate);
                if (cell.isMined() && !cell.getTerrain().hasFluids() && cell.getEntity() == null
                        && reserved.add(key)) {
                    return candidate;
                }
            }
        }
        reserved.add(fallback.x + "," + fallback.y + "," + fallback.z);
        return fallback;
    }

    private int automateAction(String actionID, int target) {
        if (ActionManager.getItem(actionID) == null) {
            return 0;
        }
        int current = world.getTaskManager().getNumItemsOnAutomatedQueue(actionID);
        while (current < target) {
            world.getTaskManager().addItemOnAutomatedQueue(actionID);
            current++;
        }
        while (current > target) {
            world.getTaskManager().removeItemOnAutomatedQueue(actionID);
            current--;
        }
        return 1;
    }

    /**
     * Queues the next safe step toward the nearest vertical stone column.
     * This is deliberately incremental: each completed step refreshes A* and
     * exposes the next layer before the following setup pass queues it.
     */
    private int queueStoneAccess(Point3D anchor) {
        Point3D stone = findStoneColumn(anchor);
        if (stone == null) {
            return 0;
        }

        for (int z = anchor.z + 1; z < stone.z; z++) {
            Cell cell = World.getCell(stone.x, stone.y, z);
            if (cell.getTerrain().hasFluids()) {
                return 0;
            }
            if (!cell.isMined()) {
                Point3D target = new Point3D(stone.x, stone.y, z);
                return issueAndCount(Task.TASK_MINE_LADDER, null, target, target);
            }
            if (!cell.isDiscovered()) {
                return 0;
            }
        }

        Cell stoneCell = World.getCell(stone);
        if (stoneCell.isMined()) {
            if (countItems("rmstone") < 8 && !hasPendingMineAt(stone.z)) {
                int fromX = Math.max(0, stone.x - 1);
                int toX = Math.min(World.getCells().length - 1, stone.x + 1);
                int fromY = Math.max(0, stone.y - 1);
                int toY = Math.min(World.getCells()[0].length - 1, stone.y + 1);
                return issueAndCount(Task.TASK_MINE, null,
                        new Point3D(fromX, fromY, stone.z), new Point3D(toX, toY, stone.z));
            }
            return 0;
        }

        Point3D ladderPoint = new Point3D(stone.x, stone.y, stone.z - 1);
        if (!hasItemAt(ladderPoint, "ladder") && !hasQueuedDestination("qladder", ladderPoint)) {
            return issueAndCount(Task.TASK_QUEUE_AND_PLACE, "qladder", ladderPoint, ladderPoint);
        }
        return issueAndCount(Task.TASK_MINE, null, stone, stone);
    }

    private Point3D findStoneColumn(Point3D anchor) {
        if (cachedStoneColumn != null) {
            return new Point3D(cachedStoneColumn);
        }
        int maxRadius = 8;
        for (int radius = 0; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) {
                        continue;
                    }
                    int x = anchor.x + dx;
                    int y = anchor.y + dy;
                    if (x < 0 || y < 0 || x >= World.getCells().length || y >= World.getCells()[0].length) {
                        continue;
                    }
                    Cell floor = World.getCell(x, y, anchor.z);
                    if (!floor.isMined() || floor.getTerrain().hasFluids()) {
                        continue;
                    }
                    for (int z = anchor.z + 2; z < World.MAP_DEPTH; z++) {
                        Cell cell = World.getCell(x, y, z);
                        TerrainManagerItem terrain = TerrainManager.getItemByID(cell.getTerrain().getTerrainID());
                        if (terrain != null && (terrain.getIniHeader().equalsIgnoreCase("stone")
                                || terrain.getIniHeader().equalsIgnoreCase("densestone"))) {
                            cachedStoneColumn = new Point3D(x, y, z);
                            return new Point3D(cachedStoneColumn);
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean hasItemAt(Point3D point, String type) {
        Cell cell = World.getCell(point);
        Item item = cell.getItem();
        return item != null && item.getIniHeader().equalsIgnoreCase(type);
    }

    private boolean hasItemInArea(String type, Point3D from, Point3D to) {
        for (Item item : World.getItems().values()) {
            if (item == null || !item.getIniHeader().equalsIgnoreCase(type) || item.getCoordinates() == null) {
                continue;
            }
            Point3DShort position = item.getCoordinates();
            if (position.x >= from.x && position.x <= to.x && position.y >= from.y && position.y <= to.y
                    && position.z == from.z) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPendingMineAt(int z) {
        List<TaskManagerItem>[] taskLists = new List[] {
                world.getTaskManager().getTaskItems(),
                world.getTaskManager().getTaskItemsTemp()
        };
        for (List<TaskManagerItem> taskList : taskLists) {
            for (TaskManagerItem item : taskList) {
                Task task = item.getTask();
                if (task != null && (task.getTask() == Task.TASK_MINE || task.getTask() == Task.TASK_MINE_LADDER)
                        && task.getPointIni() != null && task.getPointIni().z == z && !task.isFinished()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasQueuedAction(String actionID) {
        List<Action>[] queues = new List[] {
                world.getTaskManager().getCustomActions(),
                world.getTaskManager().getCustomActionsTemp(),
                world.getTaskManager().getCustomActionsWait()
        };
        for (List<Action> queue : queues) {
            for (Action action : queue) {
                if (actionID.equalsIgnoreCase(action.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasQueuedDestination(String actionID, Point3D point) {
        List<Action>[] queues = new List[] {
                world.getTaskManager().getCustomActions(),
                world.getTaskManager().getCustomActionsTemp(),
                world.getTaskManager().getCustomActionsWait()
        };
        for (List<Action> queue : queues) {
            for (Action action : queue) {
                if (!actionID.equalsIgnoreCase(action.getId()) || action.getDestinationPoint() == null) {
                    continue;
                }
                if (action.getDestinationPoint().x == point.x && action.getDestinationPoint().y == point.y
                        && action.getDestinationPoint().z == point.z) {
                    return true;
                }
            }
        }
        return false;
    }

    private CommandResult villageStatus() {
        Point3D anchor = villageOrigin();
        String[] items = new String[] {"apple", "pear", "wheat", "flour", "bread", "rmstone", "rmwood",
                "carpentrybench", "wooddetailer", "masonbench", "mill", "bakertable", "bakeroven"};
        StringBuilder result = new StringBuilder("origin=").append(anchor.x).append(' ').append(anchor.y).append(' ')
                .append(anchor.z).append(" items=");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(items[i]).append(':').append(countItems(items[i]));
        }
        result.append(" storage=prepfood(apple:").append(countItemsInStockpile("prepfood", "apple"))
                .append("/20 pear:").append(countItemsInStockpile("prepfood", "pear"))
                .append("/20 bread:").append(countItemsInStockpile("prepfood", "bread"))
                .append(") rawfood(wheat:").append(countItemsInStockpile("rawfood", "wheat"))
                .append("/10 flour:").append(countItemsInStockpile("rawfood", "flour"))
                .append("/10)")
                .append(" minimums=apple:").append(world.getTaskManager().getNumItemsOnAutomatedQueue("qharvestapple"))
                .append("+").append(world.getTaskManager().getNumItemsOnAutomatedQueue("qharvestpapple"))
                .append(" pear:").append(world.getTaskManager().getNumItemsOnAutomatedQueue("qharvestpear"))
                .append("+").append(world.getTaskManager().getNumItemsOnAutomatedQueue("qharvestppear"))
                .append(" wheat:").append(world.getTaskManager().getNumItemsOnAutomatedQueue("qharvestwildwheat"))
                .append("+").append(world.getTaskManager().getNumItemsOnAutomatedQueue("qharvestwheat"))
                .append(" flour:").append(world.getTaskManager().getNumItemsOnAutomatedQueue("qflour"))
                .append(" bread:").append(world.getTaskManager().getNumItemsOnAutomatedQueue("qbread"))
                .append("; use 'setup village' again after ticks to advance stone and dependency stages");
        return success(result.toString());
    }

    private CommandResult foodReady() {
        int apples = countItemsInStockpile("prepfood", "apple");
        int pears = countItemsInStockpile("prepfood", "pear");
        int bread = countItemsInStockpile("prepfood", "bread");
        boolean ready = apples >= 20 && pears >= 20;
        String message = (ready ? "READY" : "NOT READY")
                + " prepfood apple=" + apples + "/20 pear=" + pears + "/20 bread=" + bread
                + " total apple=" + countItems("apple") + " pear=" + countItems("pear");
        return ready ? success(message) : failure(message);
    }

    private int countItemsInStockpile(String stockpileType, String itemType) {
        int count = 0;
        for (Stockpile stockpile : world.getStockpiles()) {
            if (stockpile.getType() == null || !stockpileType.equalsIgnoreCase(stockpile.getType().getID())) {
                continue;
            }
            for (Point3DShort point : stockpile.getPoints()) {
                Item item = World.getCell(point).getItem();
                if (item != null && itemType.equalsIgnoreCase(item.getIniHeader())) {
                    count++;
                }
            }
        }
        return count;
    }

    private int countItems(String type) {
        int count = 0;
        for (Item item : World.getItems().values()) {
            if (item != null && item.getIniHeader().equalsIgnoreCase(type)) {
                count++;
            }
        }
        return count;
    }

    private Point3D villageOrigin() {
        if (cachedVillageOrigin != null) {
            return new Point3D(cachedVillageOrigin);
        }
        if (World.getCitizenIDs().isEmpty()) {
            throw new IllegalArgumentException("no starting citizens found; provide x y z");
        }
        Citizen citizen = (Citizen) World.getLivingEntityByID(World.getCitizenIDs().get(0));
        if (citizen == null || citizen.getCoordinates() == null) {
            throw new IllegalArgumentException("starting citizen has no position; provide x y z");
        }
        cachedVillageOrigin = new Point3D(citizen.getCoordinates().x, citizen.getCoordinates().y,
                citizen.getCoordinates().z);
        return new Point3D(cachedVillageOrigin);
    }

    private Point3D offset(Point3D point, int dx, int dy) {
        return new Point3D(point.x + dx, point.y + dy, point.z);
    }

    private Point3D bounded(Point3D point) {
        return new Point3D(Math.max(0, Math.min(World.getCells().length - 1, point.x)),
                Math.max(0, Math.min(World.getCells()[0].length - 1, point.y)), point.z);
    }

    /** Same order-creation path used by the windowed command panel. */
    private Task issue(int taskType, String parameter, String parameter2, Point3D from, Point3D to) {
        if (from != null) {
            validatePoint(from);
            validatePoint(to);
        }
        int stockpileCountBefore = taskType == Task.TASK_STOCKPILE
                ? world.getStockpiles().size() : -1;
        int zoneCountBefore = taskType == Task.TASK_CREATE_ZONE
                ? world.getZones().size() : -1;
        int customActionCountBefore = isImmediateQueueTask(taskType)
                ? customActionCount() : -1;
        Game.createTask(taskType);
        Task task = Game.getCurrentTask();
        if (task == null) {
            return null;
        }
        lastTaskId = task.getID();
        if (parameter != null) {
            task.setParameter(parameter);
            task = Game.getCurrentTask();
            if (task == null) {
                return null;
            }
            lastTaskId = task.getID();
        }
        if (parameter2 != null) {
            task.setParameter2(parameter2);
            task = Game.getCurrentTask();
            if (task == null) {
                return null;
            }
            lastTaskId = task.getID();
        }

        if (task.getState() == Task.STATE_CREATED) {
            world.getTaskManager().addTask(task);
            Game.setCurrentState(Game.STATE_NO_STATE);
        } else if (task.getState() == Task.STATE_CREATING_SINGLEPOINT) {
            if (from == null) {
                throw new IllegalArgumentException("task requires a point");
            }
            task.setPoint(new Point3D(from));
        } else {
            if (from == null || to == null) {
                throw new IllegalArgumentException("task requires one or two points");
            }
            task.setPoint(new Point3D(from));
            task = Game.getCurrentTask();
            if (task == null) {
                return null;
            }
            task.setPoint(new Point3D(to));
        }
        Task result = Game.getCurrentTask();
        if (result == null && taskType == Task.TASK_STOCKPILE
                && world.getStockpiles().size() > stockpileCountBefore) {
            // Stockpiles are materialized immediately by the legacy task path,
            // which then clears its temporary task because no citizen hotpoints
            // are needed. Report the model change as a successful order.
            return task;
        }
        if (result == null && taskType == Task.TASK_CREATE_ZONE
                && world.getZones().size() > zoneCountBefore) {
            // Zones are also created immediately and have no citizen hotpoints;
            // the legacy task path clears the temporary task after creation.
            return task;
        }
        if (result == null && isImmediateQueueTask(taskType)
                && customActionCount() > customActionCountBefore) {
            // Queue-and-place orders become custom actions immediately and do
            // not retain a citizen hotpoint task in the legacy model.
            return task;
        }
        return result;
    }

    private boolean isImmediateQueueTask(int taskType) {
        return taskType == Task.TASK_CUSTOM_ACTION
                || taskType == Task.TASK_QUEUE_AND_PLACE
                || taskType == Task.TASK_QUEUE_AND_PLACE_ROW
                || taskType == Task.TASK_QUEUE_AND_PLACE_AREA;
    }

    private int customActionCount() {
        return world.getTaskManager().getCustomActions().size()
                + world.getTaskManager().getCustomActionsTemp().size()
                + world.getTaskManager().getCustomActionsWait().size();
    }

    private Point3D point(List<String> words, int offset) {
        return new Point3D(parseInt(words.get(offset), "x"),
                parseInt(words.get(offset + 1), "y"), parseInt(words.get(offset + 2), "z"));
    }

    private void validatePoint(Point3D point) {
        if (point.x < 0 || point.y < 0 || point.z < 0
                || point.x >= World.getCells().length
                || point.y >= World.getCells()[0].length
                || point.z >= World.getCells()[0][0].length) {
            throw new IllegalArgumentException("point outside world: " + point.x + " " + point.y + " " + point.z);
        }
    }

    private String statusText() {
        int livings = World.getLivings(true).size() + World.getLivings(false).size();
        return "date=" + world.getDate().getDay() + "/" + world.getDate().getMonth() + "/" + world.getDate().getYear()
                + " citizens=" + World.getCitizenIDs().size()
                + " livings=" + livings
                + " items=" + World.getItems().size()
                + " coins=" + world.getCoins()
                + " view=" + world.getView()
                + " floors=" + world.getNumFloorsDiscovered()
                + " speed=" + World.SPEED
                + " paused=" + Game.isPaused()
                + " ticks=" + ticksAdvanced;
    }

    private String hashText() {
        return "terrain-hash=" + Long.toHexString(TownsHeadless.computeTerrainHash())
                + " state-hash=" + Long.toHexString(TownsHeadless.computeStateHash());
    }

    private static String helpText() {
        return "commands: " + String.join(", ", CommandRegistry.humanCommands()) + ". Syntax: status/world, cell x y z, livings [id], items [id], tasks, why task-or-action-id, queues [action-id|summary], progress, buildings, zones, stockpiles, "
                + "hash, origin, tick [count], setup village [x y z|status|auto [cycles]], food-ready, order task-name [parameter] [parameter2] [x y z [x2 y2 z2]], "
                + "save name, pause, resume, quit. Shortcuts: mine, mine-area, dig, build, stockpile, village. "
                + "Use 'commands' for task names and syntax.";
    }

    private static String commandCatalog() {
        return "inspection: status|world, cell x y z, livings [id], items [id], tasks, queues [action-id|summary], progress, food-ready, buildings, zones, stockpiles, catalog items|terrain|buildings|actions|zones|livings, hash\n"
                + "simulation: tick [count]|step [count], pause, resume, toggle-pause, view x y z, speed up|down, save name, quit\n"
                + "orders: mine x y z, mine-area x1 y1 z1 x2 y2 z2, dig x y z, build id x y z, "
                + "stockpile kind x1 y1 z1 x2 y2 z2\n"
                + "generic task names: mine, mine-ladder, dig, cancel-order, wear, wear-off, convert-civilian, "
                + "convert-soldier, fight, heal, autoequip, soldier-set-state, soldier-add-patrol, "
                + "soldier-remove-patrol, soldier-add-patrol-group, soldier-remove-patrol-group, build, "
                + "destroy-building, turn-off, turn-on, terrain-raise, terrain-lower, terrain-change, fluid-add, "
                + "fluid-remove, create-place, remove-building-task, create-in-building, create, destroy-entity, "
                + "create-place-row, lock, unlock-open, unlock-close, stockpile, delete-stockpile, create-zone, "
                + "delete-zone, expand-zone, change-owner, change-owner-group, custom-action, queue, queue-place, "
                + "queue-place-row, queue-place-area, move-to-caravan, food-needed.\n"
                + "automate action-id minimum sets a production minimum; automation [action-id] inspects rules; setup village creates a compact starter layout, auto runs staged discovery, and progress shows resource counts; generic syntax: order task-name "
                + "[parameter] [parameter2] [x y z [x2 y2 z2]]; omit values when not needed.";
    }

    private static <T> List<T> sortedValues(java.util.Map<String, T> values) {
        List<String> keys = new ArrayList<String>(values.keySet());
        Collections.sort(keys);
        List<T> sorted = new ArrayList<T>(keys.size());
        for (String key : keys) {
            sorted.add(values.get(key));
        }
        return sorted;
    }

    private static int taskType(String name) {
        switch (name.toLowerCase()) {
            case "mine": return Task.TASK_MINE;
            case "mine-ladder": return Task.TASK_MINE_LADDER;
            case "dig": return Task.TASK_DIG;
            case "cancel-order": return Task.TASK_CANCEL_ORDER;
            case "wear": return Task.TASK_WEAR;
            case "wear-off": return Task.TASK_WEAR_OFF;
            case "convert-civilian": return Task.TASK_CONVERT_TO_CIVILIAN;
            case "convert-soldier": return Task.TASK_CONVERT_TO_SOLDIER;
            case "fight": return Task.TASK_FIGHT;
            case "heal": return Task.TASK_HEAL;
            case "autoequip": return Task.TASK_AUTOEQUIP;
            case "soldier-set-state": return Task.TASK_SOLDIER_SET_STATE;
            case "soldier-add-patrol": return Task.TASK_SOLDIER_ADD_PATROL_POINT;
            case "soldier-remove-patrol": return Task.TASK_SOLDIER_REMOVE_PATROL_POINT;
            case "soldier-add-patrol-group": return Task.TASK_SOLDIER_ADD_PATROL_POINT_GROUP;
            case "soldier-remove-patrol-group": return Task.TASK_SOLDIER_REMOVE_PATROL_POINT_GROUP;
            case "build": return Task.TASK_BUILD;
            case "destroy-building": return Task.TASK_DESTROY_BUILDING;
            case "turn-off": return Task.TASK_TURN_OFF_NON_STOP;
            case "turn-on": return Task.TASK_TURN_ON_NON_STOP;
            case "terrain-raise": return Task.TASK_TERRAIN_RAISE;
            case "terrain-lower": return Task.TASK_TERRAIN_LOWER;
            case "terrain-change": return Task.TASK_TERRAIN_CHANGE;
            case "fluid-add": return Task.TASK_TERRAIN_ADD_FLUID;
            case "fluid-remove": return Task.TASK_TERRAIN_REMOVE_FLUID;
            case "create-place": return Task.TASK_CREATE_AND_PLACE;
            case "remove-building-task": return Task.TASK_REMOVE_BUILDING_TASK;
            case "create-in-building": return Task.TASK_CREATE_IN_A_BUILDING;
            case "create": return Task.TASK_CREATE;
            case "destroy-entity": return Task.TASK_DESTROY_ENTITY;
            case "create-place-row": return Task.TASK_CREATE_AND_PLACE_ROW;
            case "lock": return Task.TASK_LOCK;
            case "unlock-open": return Task.TASK_UNLOCK_OPEN;
            case "unlock-close": return Task.TASK_UNLOCK_CLOSE;
            case "stockpile": return Task.TASK_STOCKPILE;
            case "delete-stockpile": return Task.TASK_DELETE_STOCKPILE;
            case "create-zone": return Task.TASK_CREATE_ZONE;
            case "delete-zone": return Task.TASK_DELETE_ZONE;
            case "expand-zone": return Task.TASK_EXPAND_ZONE;
            case "change-owner": return Task.TASK_CHANGE_OWNER;
            case "change-owner-group": return Task.TASK_CHANGE_OWNER_GROUP;
            case "custom-action": return Task.TASK_CUSTOM_ACTION;
            case "queue": return Task.TASK_QUEUE;
            case "queue-place": return Task.TASK_QUEUE_AND_PLACE;
            case "queue-place-row": return Task.TASK_QUEUE_AND_PLACE_ROW;
            case "queue-place-area": return Task.TASK_QUEUE_AND_PLACE_AREA;
            case "move-to-caravan": return Task.TASK_MOVE_TO_CARAVAN;
            case "food-needed": return Task.TASK_FOOD_NEEDED;
            default: throw new IllegalArgumentException("unknown task name: " + name);
        }
    }

    private static List<String> tokenize(String line) {
        List<String> words = new ArrayList<String>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    word.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (word.length() > 0) {
                    words.add(word.toString());
                    word.setLength(0);
                }
            } else {
                word.append(c);
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("unterminated quote");
        }
        if (word.length() > 0) {
            words.add(word.toString());
        }
        return words;
    }

    private static void require(List<String> words, int count, String command) {
        if (words.size() != count) {
            throw new IllegalArgumentException("usage: " + command);
        }
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
    }

    private static boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static long parseNonNegativeLong(String value, String name) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
    }

    private static CommandResult success(String message) {
        return new CommandResult(true, message);
    }

    private static CommandResult failure(String message) {
        return new CommandResult(false, message == null ? "command failed" : message);
    }

    public static final class CommandResult {
        private final boolean successful;
        private final String message;

        private CommandResult(boolean successful, String message) {
            this.successful = successful;
            this.message = message;
        }

        public boolean isSuccessful() {
            return successful;
        }

        public String getMessage() {
            return message;
        }
    }
}
