package xaos.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.Gson;
import xaos.TownsHeadless;
import xaos.main.Game;
import xaos.main.World;
import xaos.campaign.MissionData;
import xaos.tiles.Cell;
import xaos.tiles.entities.living.LivingEntity;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.living.Citizen;
import xaos.tasks.Task;
import xaos.tasks.TaskManagerItem;
import xaos.actions.ActionManager;
import xaos.actions.ActionManagerItem;

/** Versioned, line-oriented machine protocol layered on GameCommandShell. */
public final class GameCommandProtocol {
    public static final String VERSION = "2.0";
    private final GameCommandShell shell;
    private final String mode;
    private final String seedMetadata;
    private long revision = 0;
    private long nextActionId = 1;
    private long nextEventId = 1;
    private final List<Map<String,Object>> events = new ArrayList<>();
    private final Map<Long,Map<String,Object>> actionInfo = new HashMap<>();
    private final Set<Long> startedActions = new HashSet<>();
    private final Set<Long> completedActions = new HashSet<>();
    private final Map<Long,Map<String,Object>> revisionSnapshots = new LinkedHashMap<>();
    private String worldId = UUID.randomUUID().toString();
    private final BufferedWriter transcript;
    private long errorCount;
    private long assertionCount;
    private List<String> warnings = List.of();
    private int lastCitizenCount;
    private int lastItemCount;
    private int lastBuildingCount;
    private String lastDate;

    public GameCommandProtocol(GameCommandShell shell) {
        this(shell, null, "test");
    }

    public GameCommandProtocol(GameCommandShell shell, Path transcriptPath) {
        this(shell, transcriptPath, "test");
    }

    public GameCommandProtocol(GameCommandShell shell, Path transcriptPath, String mode) {
        this(shell, transcriptPath, mode, "unknown");
    }

    public GameCommandProtocol(GameCommandShell shell, Path transcriptPath, String mode, String seedMetadata) {
        this.shell = shell;
        if (!List.of("play", "test", "debug").contains(mode)) throw new IllegalArgumentException("mode must be play, test, or debug");
        this.mode = mode;
        this.seedMetadata = seedMetadata == null ? "unknown" : seedMetadata;
        lastCitizenCount=World.getCitizenIDs().size(); lastItemCount=World.getItems().size(); lastBuildingCount=World.getBuildings().size(); lastDate=dateKey();
        BufferedWriter writer = null;
        if (transcriptPath != null) {
            try {
                Path parent = transcriptPath.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                writer = Files.newBufferedWriter(transcriptPath);
                Map<String,Object> header = new LinkedHashMap<>();
                header.put("record", "header"); header.put("protocol_version", VERSION);
                header.put("java_version", System.getProperty("java.version"));
                header.put("world_id", worldId); header.put("deterministic", true);
                header.put("seed", this.seedMetadata);
                header.put("initial_terrain_hash", Long.toHexString(TownsHeadless.computeTerrainHash()));
                header.put("initial_state_hash", Long.toHexString(TownsHeadless.computeStateHash()));
                writeTranscript(header, writer);
            } catch (IOException e) {
                throw new IllegalArgumentException("could not create transcript: " + e.getMessage(), e);
            }
        }
        transcript = writer;
    }

    /** Parses one protocol JSON value for the headless replay verifier. */
    public static Object parseJson(String line) {
        return Json.parse(line);
    }

    public boolean isExitRequested() {
        return shell.isExitRequested();
    }

    public String execute(String line) {
        Object parsed;
        String id = null;
        warnings = List.of();
        try {
            parsed = Json.parse(line);
            Map<String, Object> request = Json.object(parsed);
            warnings = unknownFields(request, Set.of("id", "op", "args", "protocol_version", "if_revision", "timeout_ms", "strict"));
            if (Boolean.TRUE.equals(request.get("strict")) && !warnings.isEmpty())
                throw new ProtocolException("UNKNOWN_FIELD", warnings.get(0));
            id = Json.string(request.get("id"), "request");
            String op = Json.string(request.get("op"), "op");
            Map<String, Object> args = request.get("args") == null
                    ? new LinkedHashMap<String, Object>() : Json.object(request.get("args"));
            if (request.containsKey("protocol_version") && !VERSION.equals(request.get("protocol_version")))
                throw new ProtocolException("UNSUPPORTED_VERSION", "protocol_version must be " + VERSION);
            if (request.containsKey("if_revision") && Json.longValue(request.get("if_revision"), "if_revision") != revision)
                throw new ProtocolException("STALE_REVISION", "request revision does not match current revision");
            Object result = dispatch(op, args);
            String output = response(id, true, result, null);
            record(line, output);
            return output;
        } catch (ProtocolException e) {
            errorCount++;
            String output = response(id, false, null, error(e.code, e.getMessage()));
            record(line, output);
            return output;
        } catch (RuntimeException e) {
            errorCount++;
            String output = response(id, false, null, error("INTERNAL_ERROR", e.getMessage()));
            record(line, output);
            return output;
        }
    }

    /** Writes the transcript footer and closes its file, if enabled. */
    public void closeTranscript() {
        if (transcript == null) return;
        Map<String,Object> footer = new LinkedHashMap<>();
        footer.put("record", "footer"); footer.put("tick", shell.getTicksAdvanced());
        footer.put("revision", revision); footer.put("error_count", errorCount);
        footer.put("assertion_count", assertionCount);
        footer.put("state_hash", Long.toHexString(TownsHeadless.computeStateHash()));
        footer.put("terrain_hash", Long.toHexString(TownsHeadless.computeTerrainHash()));
        footer.put("exit_reason", shell.isExitRequested() ? "quit" : "eof");
        try { writeTranscript(footer, transcript); transcript.close(); }
        catch (IOException e) { throw new IllegalStateException("could not close transcript: " + e.getMessage(), e); }
    }

    private void record(String request, String response) {
        if (transcript == null) return;
        Map<String,Object> item = new LinkedHashMap<>(); item.put("record", "exchange");
        item.put("request", request); item.put("response", Json.parse(response));
        try { writeTranscript(item, transcript); }
        catch (IOException e) { throw new IllegalStateException("could not write transcript: " + e.getMessage(), e); }
    }

    private static void writeTranscript(Map<String,Object> value, BufferedWriter writer) throws IOException {
        writer.write(Json.encode(value)); writer.newLine(); writer.flush();
    }

    private Object dispatch(String op, Map<String, Object> args) {
        if (mode.equals("play") && (op.equals("assert") || op.equals("checkpoint"))) throw new ProtocolException("MODE_RESTRICTED", op+" requires test mode");
        switch (op) {
            case "capabilities": return capabilities();
            case "describe": return describe(args);
            case "observe": return observe(args);
            case "assert": return assertion(args);
            case "advance": return advance(args);
            case "act": return act(args);
            case "checkpoint": return checkpoint(args);
            default: throw new ProtocolException("UNKNOWN_OPERATION", "unknown operation: " + op);
        }
    }

    private Map<String, Object> capabilities() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("protocol_version", VERSION);
        List<String> operations=new ArrayList<>(List.of("capabilities","describe","observe","act","advance")); if(!mode.equals("play")){operations.add("assert");operations.add("checkpoint");} m.put("operations", operations);
        m.put("mode", mode);
        m.put("deterministic", true);
        m.put("world_id", worldId);
        m.put("seed", seedMetadata);
        m.put("features", Map.of("saving", !mode.equals("play"), "debug_actions", false, "event_stream", true, "delta_observation", true));
        m.put("limits", Map.of("max_ticks_per_advance", 1_000_000, "max_page_size", 500, "max_response_bytes", 2_000_000, "max_spatial_volume", 100_000, "event_history", 512));
        return m;
    }

    private Object describe(Map<String, Object> args) {
        String action = args.containsKey("action") ? Json.string(args.get("action"), "action") : null;
        if (args.containsKey("catalog")) {
            String catalog = Json.string(args.get("catalog"), "catalog");
            if (!List.of("items","terrain","buildings","actions","zones","livings").contains(catalog)) throw new ProtocolException("INVALID_ARGUMENT", "unknown catalog: " + catalog);
            Map<String,Object> out=new LinkedHashMap<>(); out.put("catalog",catalog); out.put("filter",args.getOrDefault("filter",null)); out.put("records",records(shell.execute("catalog "+catalog).getMessage(),catalog,500,args.get("filter"))); return out;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (action == null) {
            m.put("operations", capabilities().get("operations"));
            m.put("operation_schemas", operationSchemas());
            m.put("actions", List.of(actionSchema("mine"), actionSchema("mine_area"), actionSchema("dig"), actionSchema("build"), actionSchema("stockpile")));
            return m;
        }
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("action", action);
        schema.put("type", "player_action");
        schema.put("required", List.of("target"));
        schema.put("optional", List.of("client_tag", "dry_run"));
        schema.put("target", Map.of("type", "object", "shape", action.equals("mine_area") ? "from/to" : "x/y/z"));
        if (action.equals("mine") || action.equals("dig") || action.equals("mine_area") || action.equals("build") || action.equals("stockpile")) { /* known */ }
        else throw new ProtocolException("UNKNOWN_ACTION", "unknown action: " + action);
        m.put("schema", schema);
        return m;
    }

    private Map<String,Object> actionSchema(String name) {
        Map<String,Object> s = new LinkedHashMap<>(); s.put("id", name); s.put("type", "player_action");
        s.put("required", name.equals("mine_area") ? List.of("target") : List.of("target"));
        s.put("optional", List.of("client_tag", "dry_run")); s.put("lifecycle", List.of("accepted","active","blocked","completed","cancelled","failed"));
        return s;
    }

    private List<ProtocolTypes.CommandDescriptor> operationSchemas(){return CommandRegistry.operations(!mode.equals("play"));}

    private Object observe(Map<String, Object> args) {
        Map<String, Object> summary = summary();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Set<String> include = new LinkedHashSet<>();
        if (args.get("include") instanceof List) for (Object v : (List<?>)args.get("include")) include.add(Json.string(v,"include"));
        if (include.isEmpty()) include.addAll(List.of("summary","map","citizens","items","tasks","buildings","zones","stockpiles","production","events"));
        int limit = pageLimit(args);
        int offset = cursorOffset(args.get("cursor"));
        if (include.contains("summary")) result.put("summary", summary);
        if (include.contains("map")) result.put("map", mapSnapshot());
        if (include.contains("citizens")) result.put("citizens", citizenRecords(args, limit, offset));
        if (include.contains("items")) result.put("items", itemRecords(args, limit, offset));
        if (include.contains("tasks")) result.put("tasks", taskRecords(limit, offset));
        if (include.contains("buildings")) result.put("buildings", records(shell.execute("buildings").getMessage(), "building", limit, null, offset));
        if (include.contains("zones")) result.put("zones", records(shell.execute("zones").getMessage(), "zone", limit, null, offset));
        if (include.contains("stockpiles")) result.put("stockpiles", records(shell.execute("stockpiles").getMessage(), "stockpile", limit, null, offset));
        if (include.contains("production")) result.put("production", productionRecords(limit, offset));
        if (include.contains("objectives")) result.put("objectives", objectiveSnapshot());
        Map<String,Object> pagination=new LinkedHashMap<>(); pagination.put("cursor",args.getOrDefault("cursor",null)); pagination.put("next_cursor","p:"+worldId+":"+(offset+limit)); pagination.put("limit",limit); result.put("pagination",pagination);
        if (include.contains("events")) result.put("events", new ArrayList<>(events));
        result.put("tick", shell.getTicksAdvanced());
        result.put("revision", revision);
        result.put("world_id", worldId);
        result.put("hashes", Map.of("state", Long.toHexString(TownsHeadless.computeStateHash()), "terrain", Long.toHexString(TownsHeadless.computeTerrainHash())));
        if (args.containsKey("since_revision")) {
            long since=Json.longValue(args.get("since_revision"),"since_revision");
            Map<String,Object> previous=revisionSnapshots.get(since);
            if(previous==null) throw new ProtocolException("DELTA_UNAVAILABLE","no snapshot retained for revision "+since);
            result.put("delta",delta(previous,result,since,revision));
        }
        Map<String,Object> stored=new LinkedHashMap<>(result); stored.remove("delta"); revisionSnapshots.put(revision,stored); while(revisionSnapshots.size()>64)revisionSnapshots.remove(revisionSnapshots.keySet().iterator().next());
        return result;
    }

    private Map<String,Object> mapSnapshot() { Cell[][][] c=World.getCells(); Map<String,Object> m=new LinkedHashMap<>(); m.put("dimensions",List.of(c.length,c[0].length,c[0][0].length)); m.put("origin",coordinates(shell.execute("origin").getMessage())); m.put("view",String.valueOf(shell.getWorld().getView())); m.put("discovered_floors",shell.getWorld().getNumFloorsDiscovered()); return m; }
    private Map<String,Object> objectiveSnapshot(){Map<String,Object> result=new LinkedHashMap<>();MissionData mission=Game.getCurrentMissionData();result.put("available",mission!=null);if(mission==null){result.put("records",List.of());return result;}result.put("mission_id",mission.getId());result.put("name",mission.getName());result.put("text",mission.getText());result.put("tutorial_flow_index",mission.getTutorialFlowIndex());result.put("tutorial_flow_count",mission.getTutorialFlows()==null?0:mission.getTutorialFlows().size());result.put("records",List.of());return result;}
    private List<Object> citizenRecords(Map<String,Object> args,int limit,int offset) { List<Integer> ids=new ArrayList<>(World.getCitizenIDs()); java.util.Collections.sort(ids); List<Object> out=new ArrayList<>(); int seen=0; for(Integer id:ids){LivingEntity living=World.getLivingEntityByID(id); if(living==null||!matchesSpatial(args,living.getCoordinates().x,living.getCoordinates().y,living.getCoordinates().z)) continue; if(seen++<offset)continue; Map<String,Object> r=new LinkedHashMap<>();r.put("kind","citizen");r.put("id",id);r.put("type",living.getIniHeader());r.put("position",position(living.getCoordinates().x,living.getCoordinates().y,living.getCoordinates().z));r.put("health",living.getLivingEntityData().getHealthPoints());out.add(r);if(out.size()>=limit)break;}return out; }
    private List<Object> itemRecords(Map<String,Object> args,int limit,int offset) { List<Integer> ids=new ArrayList<>(World.getItems().keySet());java.util.Collections.sort(ids);List<Object> out=new ArrayList<>();int seen=0;for(Integer id:ids){Item item=World.getItems().get(id);if(item==null||!matchesSpatial(args,item.getCoordinates().x,item.getCoordinates().y,item.getCoordinates().z))continue;if(seen++<offset)continue;Map<String,Object> r=new LinkedHashMap<>();r.put("kind","item");r.put("id",id);r.put("type",item.getIniHeader());r.put("position",position(item.getCoordinates().x,item.getCoordinates().y,item.getCoordinates().z));r.put("locked",item.isLocked());out.add(r);if(out.size()>=limit)break;}return out; }
    private List<Object> taskRecords(int limit,int offset){List<TaskManagerItem> items=new ArrayList<>();items.addAll(shell.getWorld().getTaskManager().getTaskItems());items.addAll(shell.getWorld().getTaskManager().getTaskItemsTemp());items.sort((a,b)->Integer.compare(a.getTask().getID(),b.getTask().getID()));List<Object> out=new ArrayList<>();int seen=0;for(TaskManagerItem item:items){Task task=item.getTask();if(task==null||seen++<offset)continue;List<Map<String,Object>> blockers=diagnoseBlockers(item);Map<String,Object> r=new LinkedHashMap<>();r.put("kind","task");r.put("id",task.getID());r.put("type",task.getTask());r.put("state",task.isFinished()?"completed":blockers.isEmpty()?"pending":"blocked");r.put("parameter",task.getParameter());if(task.getPointIni()!=null)r.put("from",position(task.getPointIni().x,task.getPointIni().y,task.getPointIni().z));if(task.getPointEnd()!=null)r.put("to",position(task.getPointEnd().x,task.getPointEnd().y,task.getPointEnd().z));r.put("assignees",item.getListCitizens().size());r.put("blockers",blockers);out.add(r);if(out.size()>=limit)break;}return out;}
    private List<Map<String,Object>> diagnoseBlockers(TaskManagerItem item){List<Map<String,Object>> blockers=new ArrayList<>();Task task=item.getTask();if(task==null||task.isFinished())return blockers;if(item.getListCitizens().isEmpty()){boolean idle=false;for(Integer id:World.getCitizenIDs()){LivingEntity living=World.getLivingEntityByID(id);if(living instanceof Citizen&&((Citizen)living).isIdle()&&((Citizen)living).getCurrentTask()==null){idle=true;break;}}if(!idle)blockers.add(Map.of("code","NO_WORKER","message","no idle citizen can currently accept this task"));}if(task.getPointIni()!=null){int x=task.getPointIni().x,y=task.getPointIni().y,z=task.getPointIni().z;Cell[][][] cells=World.getCells();if(x<0||y<0||z<0||x>=cells.length||y>=cells[0].length||z>=cells[0][0].length)blockers.add(Map.of("code","INVALID_TERRAIN","message","task target is outside the world bounds"));}return blockers;}
    private List<Object> productionRecords(int limit,int offset){List<ActionManagerItem> actions=new ArrayList<>(ActionManager.getAllItems().values());actions.sort((a,b)->a.getId().compareTo(b.getId()));List<Object> out=new ArrayList<>();int seen=0;for(ActionManagerItem action:actions){if(seen++<offset)continue;Map<String,Object> r=new LinkedHashMap<>();r.put("kind","production");r.put("id",action.getId());r.put("name",action.getName());r.put("generated_item",action.getGeneratedItem());r.put("minimum",shell.getWorld().getTaskManager().getNumItemsOnAutomatedQueue(action.getId()));r.put("turns",action.getTurns());r.put("priority",action.getPriorityID());r.put("state",r.get("minimum").equals(0)?"idle":"queued");out.add(r);if(out.size()>=limit)break;}return out;}
    private Map<String,Object> position(int x,int y,int z){return Map.of("x",x,"y",y,"z",z);}
    private boolean matchesSpatial(Map<String,Object> args,int x,int y,int z){
        if(args.get("box") instanceof Map){Map<String,Object> box=Json.object(args.get("box"));Map<String,Object> from=box.get("from") instanceof Map?Json.object(box.get("from")):box;Map<String,Object> to=box.get("to") instanceof Map?Json.object(box.get("to")):box;if(!between(x,from.get("x"),to.get("x"))||!between(y,from.get("y"),to.get("y"))||!between(z,from.get("z"),to.get("z")))return false;}
        if(args.get("near") instanceof Map){Map<String,Object> near=Json.object(args.get("near"));long nx=Json.longValue(near.get("x"),"near.x"),ny=Json.longValue(near.get("y"),"near.y"),nz=Json.longValue(near.get("z"),"near.z"),radius=Json.longValue(near.get("radius"),"near.radius");if(radius<0||Math.abs(x-nx)>radius||Math.abs(y-ny)>radius||Math.abs(z-nz)>radius)return false;}
        return true;
    }
    private boolean between(int value,Object a,Object b){long low=Json.longValue(a,"box");long high=Json.longValue(b,"box");return value>=Math.min(low,high)&&value<=Math.max(low,high);}
    private Map<String,Object> coordinates(String text) { Map<String,Object> m=new LinkedHashMap<>(); String[] p=text.replaceAll(".*origin=","").trim().split(" "); if(p.length>=3) {m.put("x",Long.parseLong(p[0]));m.put("y",Long.parseLong(p[1]));m.put("z",Long.parseLong(p[2]));} return m; }
    private List<Object> records(String text,String kind,int limit) { return records(text,kind,limit,null,0); }
    private List<Object> records(String text,String kind,int limit,Object filter) { return records(text,kind,limit,filter,0); }
    private List<Object> records(String text,String kind,int limit,Object filter,int offset) { List<Object> out=new ArrayList<>(); String wanted=filter==null?null:String.valueOf(filter).toLowerCase(); int seen=0; for(String line:text.split("\\n")) { if(line.trim().isEmpty()||line.startsWith("no ")) continue; if(wanted!=null&&!line.toLowerCase().contains(wanted)) continue; if(seen++<offset) continue; Map<String,Object> r=new LinkedHashMap<>(); r.put("kind",kind); for(String token:line.trim().split(" ")) { int p=token.indexOf('='); if(p>0) r.put(token.substring(0,p),numberOrString(token.substring(p+1))); } out.add(r); if(out.size()>=limit) break; } return out; }
    private int cursorOffset(Object value) { if(value==null) return 0; String cursor=Json.string(value,"cursor"); String prefix="p:"+worldId+":"; if(!cursor.startsWith(prefix)) throw new ProtocolException("INVALID_CURSOR","cursor belongs to another world epoch"); try { int offset=Integer.parseInt(cursor.substring(prefix.length())); if(offset<0) throw new NumberFormatException(); return offset; } catch(NumberFormatException e) { throw new ProtocolException("INVALID_CURSOR","invalid cursor"); } }
    private Map<String,Object> delta(Map<String,Object> previous,Map<String,Object> current,long from,long to) { Map<String,Object> d=new LinkedHashMap<>();d.put("from_revision",from);d.put("to_revision",to);List<Object> added=new ArrayList<>(),updated=new ArrayList<>(),removed=new ArrayList<>();Set<String> sections=new LinkedHashSet<>(previous.keySet());sections.addAll(current.keySet());for(String section:sections){Object oldValue=previous.get(section),newValue=current.get(section);if(!(oldValue instanceof List)||!(newValue instanceof List))continue;Map<String,Object> oldById=indexRecords((List<?>)oldValue),newById=indexRecords((List<?>)newValue);for(String id:newById.keySet()){if(!oldById.containsKey(id))added.add(newById.get(id));else if(!Json.encode(oldById.get(id)).equals(Json.encode(newById.get(id))))updated.add(newById.get(id));}for(String id:oldById.keySet())if(!newById.containsKey(id))removed.add(oldById.get(id));}d.put("added",added);d.put("updated",updated);d.put("removed",removed);return d; }
    private Map<String,Object> indexRecords(List<?> values){Map<String,Object> indexed=new LinkedHashMap<>();int ordinal=0;for(Object value:values){if(value instanceof Map){Map<?,?> map=(Map<?,?>)value;Object kind=map.get("kind"),id=map.get("id");indexed.put(String.valueOf(kind)+":"+(id==null?ordinal++:id),value);}else indexed.put(String.valueOf(ordinal++),value);}return indexed;}
    private int pageLimit(Map<String,Object> args) { long n=args.containsKey("limit")?Json.longValue(args.get("limit"),"limit"):100; if(n<1||n>500) throw new ProtocolException("LIMIT_EXCEEDED","limit must be between 1 and 500"); return (int)n; }

    private Object assertion(Map<String, Object> args) {
        assertionCount++;
        Map<String, Object> condition = Json.object(args.get("condition"));
        if (condition.containsKey("all") || condition.containsKey("any")) return Map.of("passed", evaluate(condition), "expression", condition);
        if ("food_ready".equals(condition.get("domain"))) return Map.of("passed", shell.execute("food-ready").isSuccessful(), "domain", "food_ready");
        String path = Json.string(condition.get("path"), "condition.path");
        Object actual = valueAt(path);
        String operator = condition.containsKey("op") ? Json.string(condition.get("op"), "condition.op") : "eq";
        Object expected = condition.get("value");
        boolean passed = compare(actual, operator, expected);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("passed", passed); result.put("path", path); result.put("actual", actual);
        result.put("op", operator); result.put("expected", expected);
        return result;
    }

    private Object advance(Map<String, Object> args) {
        long max = args.containsKey("max_ticks") ? Json.longValue(args.get("max_ticks"), "max_ticks") : 1;
        if (max < 0 || max > 1_000_000) throw new ProtocolException("LIMIT_EXCEEDED", "max_ticks must be between 0 and 1000000");
        Map<String, Object> until = args.containsKey("until") ? Json.object(args.get("until")) : null;
        long timeout = args.containsKey("timeout_ms") ? Json.longValue(args.get("timeout_ms"), "timeout_ms") : 60_000;
        if (timeout < 0 || timeout > 60_000) throw new ProtocolException("LIMIT_EXCEEDED", "timeout_ms must be between 0 and 60000");
        long deadline = System.currentTimeMillis() + timeout;
        long advanced = 0;
        String terminal = "max_ticks";
        long sampleEvery=args.containsKey("sample_every")?Json.longValue(args.get("sample_every"),"sample_every"):0;
        if(sampleEvery<0||sampleEvery>1_000_000) throw new ProtocolException("INVALID_ARGUMENT","sample_every must be between 0 and 1000000");
        List<Object> samples=new ArrayList<>();
        while (advanced < max) {
            if (until != null && evaluate(until)) break;
            if (Game.isPaused()) { terminal = "paused"; break; }
            if (System.currentTimeMillis() > deadline) { terminal = "timeout"; break; }
            GameCommandShell.CommandResult r = shell.execute("tick 1");
            if (!r.isSuccessful()) throw new ProtocolException("SIMULATION_ERROR", r.getMessage());
            advanced++;
            revision++;
            recordSimulationEvents();
            if(sampleEvery>0&&advanced%sampleEvery==0){Map<String,Object> sample=new LinkedHashMap<>();sample.put("tick",shell.getTicksAdvanced());sample.put("revision",revision);sample.put("summary",summary());sample.put("hashes",Map.of("state",Long.toHexString(TownsHeadless.computeStateHash()),"terrain",Long.toHexString(TownsHeadless.computeTerrainHash())));samples.add(sample);}
            if (until != null && evaluate(until)) { terminal = "condition_met"; break; }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ticks_advanced", advanced); result.put("tick", shell.getTicksAdvanced());
        if (until != null && evaluate(until)) terminal = "condition_met";
        result.put("terminal_reason", terminal);
        result.put("timed_out", "timeout".equals(terminal));
        result.put("revision", revision); result.put("events", new ArrayList<>(events)); result.put("samples",samples);
        return result;
    }

    private Object act(Map<String, Object> args) {
        if (args.get("actions") instanceof List) return actBatch(args);
        String action = Json.string(args.get("action"), "action");
        Map<String,Object> normalized = new LinkedHashMap<>(args);
        if (args.get("target") instanceof Map) normalized.putAll(flattenTarget(Json.object(args.get("target"))));
        validateTarget(action, normalized);
        String command;
        if (action.equals("mine") || action.equals("dig")) command = action + " " + ints(normalized, "x", "y", "z");
        else if (action.equals("mine_area")) command = "mine-area " + ints(normalized, "x1", "y1", "z1", "x2", "y2", "z2");
        else if (action.equals("build")) command = "build " + Json.string(normalized.get("building_id"), "building_id") + " " + ints(normalized, "x", "y", "z");
        else if (action.equals("stockpile")) command = "stockpile " + Json.string(normalized.get("kind"), "kind") + " " + ints(normalized, "x1", "y1", "z1", "x2", "y2", "z2");
        else throw new ProtocolException("UNKNOWN_ACTION", "unknown action: " + action);
        String tag = args.containsKey("client_tag") ? Json.string(args.get("client_tag"),"client_tag") : null;
        long actionId=nextActionId++;
        if (Boolean.TRUE.equals(args.get("dry_run"))) return Map.of("action_id",actionId,"accepted",true,"dry_run",true,"action",action,"normalized",normalized);
        GameCommandShell.CommandResult r = shell.execute(command);
        if (!r.isSuccessful()) throw new ProtocolException(actionErrorCode(r.getMessage()), r.getMessage());
        revision++; Map<String,Object> event=new LinkedHashMap<>(); event.put("event_id",nextEventId++);event.put("tick",shell.getTicksAdvanced());event.put("type","action.accepted");event.put("action_id",actionId);event.put("action",action);if(tag!=null)event.put("client_tag",tag);events.add(event); while(events.size()>512)events.remove(0); Map<String,Object> info=new LinkedHashMap<>(); info.put("action_id",actionId);info.put("action",action);if(tag!=null)info.put("client_tag",tag);if(shell.getLastTaskId()>=0)info.put("task_id",shell.getLastTaskId());actionInfo.put(actionId,info);
        Map<String,Object> accepted=new LinkedHashMap<>(); accepted.put("action_id",actionId); accepted.put("accepted",true); accepted.put("state","accepted"); accepted.put("action",action); accepted.put("normalized",normalized); accepted.put("message",r.getMessage()); if(shell.getLastTaskId()>=0) accepted.put("created_task_ids",List.of(Map.of("kind","task","id",shell.getLastTaskId()))); return accepted;
    }

    private Object actBatch(Map<String,Object> args) {
        List<?> raw=(List<?>)args.get("actions"); if(raw.isEmpty() || raw.size()>100) throw new ProtocolException("LIMIT_EXCEEDED","actions batch must contain 1 to 100 actions");
        List<Object> validated=new ArrayList<>();
        for(Object value:raw) { Map<String,Object> candidate=Json.object(value); Map<String,Object> dry=new LinkedHashMap<>(candidate); dry.put("dry_run",true); validated.add(act(dry)); }
        List<Object> submitted=new ArrayList<>();
        for(Object value:raw) submitted.add(act(Json.object(value)));
        return Map.of("atomic_validation",true,"accepted",true,"actions",submitted,"validation",validated);
    }

    private String actionErrorCode(String message){String text=message==null?"":message.toLowerCase();if(text.contains("outside")||text.contains("terrain"))return "INVALID_TERRAIN";if(text.contains("reserved")||text.contains("order"))return "TARGET_RESERVED";if(text.contains("worker")||text.contains("citizen"))return "NO_WORKER";if(text.contains("resource")||text.contains("material"))return "MISSING_RESOURCE";return "ACTION_REJECTED";}

    private static Map<String,Object> flattenTarget(Map<String,Object> target) { Map<String,Object> out=new LinkedHashMap<>(); Map<String,Object> from=target.get("from") instanceof Map?Json.object(target.get("from")):target; Map<String,Object> to=target.get("to") instanceof Map?Json.object(target.get("to")):from; out.put("x",from.get("x")); out.put("y",from.get("y")); out.put("z",from.get("z")); out.put("x1",from.get("x")); out.put("y1",from.get("y")); out.put("z1",from.get("z")); out.put("x2",to.get("x")); out.put("y2",to.get("y")); out.put("z2",to.get("z")); return out; }
    private void validateTarget(String action, Map<String,Object> target) { String[] names=action.equals("mine_area")||action.equals("stockpile")?new String[]{"x1","y1","z1","x2","y2","z2"}:new String[]{"x","y","z"}; for(String name:names){long value=Json.longValue(target.get(name),name); int axis=name.charAt(0)=='x'?0:name.charAt(0)=='y'?1:2; int bound=axis==0?World.getCells().length:axis==1?World.getCells()[0].length:World.getCells()[0][0].length; if(value<0||value>=bound) throw new ProtocolException("INVALID_ARGUMENT",name+" is outside world bounds");} if(names.length==6){long volume=(Math.abs(Json.longValue(target.get("x2"),"x2")-Json.longValue(target.get("x1"),"x1"))+1)*(Math.abs(Json.longValue(target.get("y2"),"y2")-Json.longValue(target.get("y1"),"y1"))+1)*(Math.abs(Json.longValue(target.get("z2"),"z2")-Json.longValue(target.get("z1"),"z1"))+1); if(volume>100_000) throw new ProtocolException("LIMIT_EXCEEDED","target volume exceeds 100000 cells");} }

    private Object checkpoint(Map<String,Object> args) {
        String name=Json.string(args.get("name"),"name");
        String mode=args.containsKey("mode")?Json.string(args.get("mode"),"mode"):"save";
        if (mode.equals("load")) {
            String zip=name.endsWith(".zip")?name:name+".zip";
            try {
                java.io.File save = new java.io.File(Game.getUserFolder()+Game.getFileSeparator()+Game.SAVE_FOLDER1+Game.getFileSeparator()+zip);
                if (!save.exists()) throw new ProtocolException("CHECKPOINT_NOT_FOUND", "checkpoint not found: "+name);
                long savedTick=0;
                Path metadata=checkpointMetadata(name);
                if(Files.exists(metadata)){try{Object raw=Json.parse(Files.readString(metadata));Map<String,Object> saved=Json.object(raw);if(saved.get("tick") instanceof Number)savedTick=((Number)saved.get("tick")).longValue();}catch(Exception ignored){/* the save itself remains authoritative */}}
                Game.continueGame(zip, null);
                shell.reloadWorld(Game.getWorld());
                shell.setTicksAdvanced(savedTick);
                revision=0; events.clear(); actionInfo.clear(); startedActions.clear(); completedActions.clear(); nextActionId=1; nextEventId=1; worldId=UUID.randomUUID().toString();
                lastCitizenCount=World.getCitizenIDs().size(); lastItemCount=World.getItems().size(); lastBuildingCount=World.getBuildings().size(); lastDate=dateKey();
                return Map.of("name",name,"mode","load","tick",savedTick,"revision",revision,"world_id",worldId,"state_hash",Long.toHexString(TownsHeadless.computeStateHash()));
            } catch (ProtocolException e) { throw e; }
            catch (RuntimeException e) { throw new ProtocolException("CHECKPOINT_LOAD_FAILED", e.getMessage()); }
        }
        if (!mode.equals("save")) throw new ProtocolException("INVALID_ARGUMENT", "checkpoint mode must be save or load");
        GameCommandShell.CommandResult r=shell.execute("save "+name);
        if(!r.isSuccessful()) throw new ProtocolException("CHECKPOINT_FAILED",r.getMessage());
        try{Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("tick",shell.getTicksAdvanced());metadata.put("revision",revision);metadata.put("state_hash",Long.toHexString(TownsHeadless.computeStateHash()));Files.writeString(checkpointMetadata(name),Json.encode(metadata));}catch(IOException e){throw new ProtocolException("CHECKPOINT_METADATA_FAILED",e.getMessage());}
        return Map.of("name",name,"mode","save","tick",shell.getTicksAdvanced(),"revision",revision,"world_id",worldId,"state_hash",Long.toHexString(TownsHeadless.computeStateHash()));
    }

    private Path checkpointMetadata(String name){return Path.of(Game.getUserFolder(),Game.SAVE_FOLDER1,name+".protocol.json");}

    private String dateKey(){return Game.getWorld().getDate().getDay()+"/"+Game.getWorld().getDate().getMonth()+"/"+Game.getWorld().getDate().getYear();}
    private void recordSimulationEvents(){
        String taskText=shell.execute("tasks").getMessage();
        for(Map.Entry<Long,Map<String,Object>> entry:actionInfo.entrySet()){
            long actionId=entry.getKey();Map<String,Object> info=entry.getValue();
            if(!startedActions.contains(actionId)){startedActions.add(actionId);addEventWithInfo("action.started",info);}
            if(!completedActions.contains(actionId)&&info.get("task_id")!=null&&!taskText.contains("id="+info.get("task_id"))){completedActions.add(actionId);addEventWithInfo("action.completed",info);}
        }
        int citizens=World.getCitizenIDs().size(),items=World.getItems().size(),buildings=World.getBuildings().size();String date=dateKey();
        if(!date.equals(lastDate)) addEvent("day.changed",Map.of("date",date));
        if(citizens<lastCitizenCount) addEvent("citizen.dead",Map.of("count",lastCitizenCount-citizens));
        if(items>lastItemCount) addEvent("item.created",Map.of("count",items-lastItemCount));
        if(buildings>lastBuildingCount) addEvent("building.completed",Map.of("count",buildings-lastBuildingCount));
        lastDate=date;lastCitizenCount=citizens;lastItemCount=items;lastBuildingCount=buildings;
    }
    private void addEvent(String type,Map<String,Object> fields){Map<String,Object> event=new LinkedHashMap<>();event.put("event_id",nextEventId++);event.put("tick",shell.getTicksAdvanced());event.put("type",type);event.putAll(fields);events.add(event);while(events.size()>512)events.remove(0);revision++;}
    private void addEventWithInfo(String type,Map<String,Object> info){Map<String,Object> event=new LinkedHashMap<>(info);event.put("event_id",nextEventId++);event.put("tick",shell.getTicksAdvanced());event.put("type",type);events.add(event);while(events.size()>512)events.remove(0);revision++;}

    private static List<String> unknownFields(Map<String,Object> m, Set<String> allowed) { List<String> result=new ArrayList<>(); for(String k:m.keySet()) if(!allowed.contains(k)) result.add("unknown request field: "+k); return result; }

    private Map<String, Object> summary() {
        String text = shell.execute("status").getMessage();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (String token : text.split(" ")) {
            int split = token.indexOf('=');
            if (split > 0) out.put(token.substring(0, split), numberOrString(token.substring(split + 1)));
        }
        return out;
    }

    private Object valueAt(String path) {
        if (path.startsWith("summary.")) return summary().get(path.substring("summary.".length()));
        if (path.equals("tick")) return shell.getTicksAdvanced();
        if (path.equals("tasks.count")) return taskRecords(500,0).size();
        if (path.equals("tasks.blocked")) {int blocked=0;for(Object record:taskRecords(500,0))if(record instanceof Map&&"blocked".equals(((Map<?,?>)record).get("state")))blocked++;return blocked;}
        if (path.equals("events.count")) return events.size();
        if (path.startsWith("inventory.")) {String wanted=path.substring("inventory.".length()).toLowerCase();int count=0;for(Object record:itemRecords(Map.of(),500,0))if(record instanceof Map&&wanted.equals(String.valueOf(((Map<?,?>)record).get("type")).toLowerCase()))count++;return count;}
        throw new ProtocolException("UNKNOWN_PATH", "unsupported observation path: " + path);
    }

    private boolean evaluate(Map<String, Object> condition) {
        if (condition.containsKey("all") || condition.containsKey("any")) { String key=condition.containsKey("all")?"all":"any"; Object raw=condition.get(key); if (!(raw instanceof List)) throw new ProtocolException("INVALID_ARGUMENT", key+" must be an array"); boolean result=key.equals("all"); for(Object value:(List<?>)raw) { boolean v=evaluate(Json.object(value)); result=key.equals("all")?(result&&v):(result||v); } return result; }
        if (condition.containsKey("event")) { String wanted=Json.string(condition.get("event"),"condition.event"); for(Map<String,Object> event:events) if(wanted.equals(event.get("type"))&&(!condition.containsKey("client_tag")||String.valueOf(condition.get("client_tag")).equals(String.valueOf(event.get("client_tag"))))) return true; return false; }
        if ("food_ready".equals(condition.get("domain"))) return shell.execute("food-ready").isSuccessful();
        String path = Json.string(condition.get("path"), "condition.path");
        return compare(valueAt(path), condition.containsKey("op") ? Json.string(condition.get("op"), "condition.op") : "eq", condition.get("value"));
    }

    private static boolean compare(Object actual, String op, Object expected) {
        if (actual instanceof Number && expected instanceof Number) {
            double a = ((Number) actual).doubleValue(), b = ((Number) expected).doubleValue();
            switch (op) { case "eq": return a == b; case "gte": return a >= b; case "gt": return a > b; case "lte": return a <= b; case "lt": return a < b; default: throw new ProtocolException("INVALID_ARGUMENT", "unknown comparison: " + op); }
        }
        if (op.equals("eq")) return actual == null ? expected == null : actual.toString().equals(String.valueOf(expected));
        throw new ProtocolException("INVALID_ARGUMENT", "comparison requires numeric values");
    }

    private static String ints(Map<String, Object> args, String... names) { StringBuilder b = new StringBuilder(); for (String n : names) { if (b.length() > 0) b.append(' '); b.append(Json.longValue(args.get(n), n)); } return b.toString(); }
    private static Object numberOrString(String s) { try { return Long.valueOf(s); } catch (NumberFormatException e) { return s; } }
    private static Map<String, Object> error(String code, String message) { return Map.of("code", code, "message", message == null ? code : message); }
    private String response(String id, boolean ok, Object result, Object error) { Map<String, Object> m = new LinkedHashMap<String, Object>(); m.put("id", id); m.put("ok", ok); m.put("protocol_version", VERSION); m.put("tick",shell.getTicksAdvanced()); m.put("revision",revision); m.put("result", ok ? result : null); m.put("warnings", warnings); if (!ok) m.put("error", error); String encoded=Json.encode(m); if(encoded.length()>2_000_000){Map<String,Object> limited=new LinkedHashMap<>();limited.put("id",id);limited.put("ok",false);limited.put("protocol_version",VERSION);limited.put("tick",shell.getTicksAdvanced());limited.put("revision",revision);limited.put("result",null);limited.put("warnings",List.of());limited.put("error",error("RESPONSE_TOO_LARGE","response exceeds maximum size of 2000000 bytes"));return Json.encode(limited);} return encoded; }

    private static final class ProtocolException extends RuntimeException { final String code; ProtocolException(String c, String m) { super(m); code = c; } }

    /** Gson-backed JSON codec for protocol envelopes. */
    static final class Json {
        private static final Gson GSON = new Gson();
        static Object parse(String s) { try { return GSON.fromJson(s,Object.class); } catch (RuntimeException e) { throw new ProtocolException("INVALID_JSON", "invalid JSON: " + e.getMessage()); } }
        static String encode(Object o) { return GSON.toJson(o); }
        @SuppressWarnings("unchecked") static Map<String,Object> object(Object o) { if (!(o instanceof Map)) throw new ProtocolException("INVALID_JSON", "expected object"); return (Map<String,Object>) o; }
        static String string(Object o, String n) { if (!(o instanceof String) || ((String)o).isEmpty()) throw new ProtocolException("INVALID_ARGUMENT", n + " must be a non-empty string"); return (String)o; }
        static long longValue(Object o, String n) { if (!(o instanceof Number)) throw new ProtocolException("INVALID_ARGUMENT", n + " must be a number"); return ((Number)o).longValue(); }
    }
}
