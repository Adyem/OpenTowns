package xaos.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned, line-oriented machine protocol layered on GameCommandShell. */
public final class GameCommandProtocol {
    public static final String VERSION = "2.0";
    private final GameCommandShell shell;

    public GameCommandProtocol(GameCommandShell shell) {
        this.shell = shell;
    }

    public String execute(String line) {
        Object parsed;
        String id = null;
        try {
            parsed = Json.parse(line);
            Map<String, Object> request = Json.object(parsed);
            id = Json.string(request.get("id"), "request");
            String op = Json.string(request.get("op"), "op");
            Map<String, Object> args = request.get("args") == null
                    ? new LinkedHashMap<String, Object>() : Json.object(request.get("args"));
            Object result = dispatch(op, args);
            return response(id, true, result, null);
        } catch (ProtocolException e) {
            return response(id, false, null, error(e.code, e.getMessage()));
        } catch (RuntimeException e) {
            return response(id, false, null, error("INTERNAL_ERROR", e.getMessage()));
        }
    }

    private Object dispatch(String op, Map<String, Object> args) {
        switch (op) {
            case "capabilities": return capabilities();
            case "describe": return describe(args);
            case "observe": return observe(args);
            case "assert": return assertion(args);
            case "advance": return advance(args);
            case "act": return act(args);
            default: throw new ProtocolException("UNKNOWN_OPERATION", "unknown operation: " + op);
        }
    }

    private Map<String, Object> capabilities() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("protocol_version", VERSION);
        m.put("operations", List.of("capabilities", "describe", "observe", "act", "advance", "assert"));
        m.put("mode", "test");
        m.put("deterministic", true);
        m.put("limits", Map.of("max_ticks_per_advance", 1_000_000, "max_page_size", 500));
        return m;
    }

    private Object describe(Map<String, Object> args) {
        String action = args.containsKey("action") ? Json.string(args.get("action"), "action") : null;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (action == null) {
            m.put("operations", capabilities().get("operations"));
            m.put("actions", List.of("mine", "mine_area", "dig", "build", "stockpile", "tick"));
            return m;
        }
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("action", action);
        schema.put("type", "player_action");
        schema.put("dry_run", true);
        if (action.equals("mine") || action.equals("dig")) schema.put("arguments", List.of("x", "y", "z"));
        else if (action.equals("mine_area")) schema.put("arguments", List.of("x1", "y1", "z1", "x2", "y2", "z2"));
        else if (action.equals("build")) schema.put("arguments", List.of("building_id", "x", "y", "z"));
        else if (action.equals("stockpile")) schema.put("arguments", List.of("kind", "x1", "y1", "z1", "x2", "y2", "z2"));
        else throw new ProtocolException("UNKNOWN_ACTION", "unknown action: " + action);
        m.put("schema", schema);
        return m;
    }

    private Object observe(Map<String, Object> args) {
        Map<String, Object> summary = summary();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("tick", shell.getTicksAdvanced());
        result.put("summary", summary);
        result.put("hash", shell.execute("hash").getMessage());
        return result;
    }

    private Object assertion(Map<String, Object> args) {
        Map<String, Object> condition = Json.object(args.get("condition"));
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
        long advanced = 0;
        while (advanced < max) {
            if (until != null && evaluate(until)) break;
            GameCommandShell.CommandResult r = shell.execute("tick 1");
            if (!r.isSuccessful()) throw new ProtocolException("SIMULATION_ERROR", r.getMessage());
            advanced++;
            if (until != null && evaluate(until)) break;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ticks_advanced", advanced); result.put("tick", shell.getTicksAdvanced());
        result.put("terminal_reason", until != null && evaluate(until) ? "condition_met" : "max_ticks");
        return result;
    }

    private Object act(Map<String, Object> args) {
        String action = Json.string(args.get("action"), "action");
        String command;
        if (action.equals("mine") || action.equals("dig")) command = action + " " + ints(args, "x", "y", "z");
        else if (action.equals("mine_area")) command = "mine-area " + ints(args, "x1", "y1", "z1", "x2", "y2", "z2");
        else if (action.equals("build")) command = "build " + Json.string(args.get("building_id"), "building_id") + " " + ints(args, "x", "y", "z");
        else if (action.equals("stockpile")) command = "stockpile " + Json.string(args.get("kind"), "kind") + " " + ints(args, "x1", "y1", "z1", "x2", "y2", "z2");
        else throw new ProtocolException("UNKNOWN_ACTION", "unknown action: " + action);
        GameCommandShell.CommandResult r = shell.execute(command);
        if (!r.isSuccessful()) throw new ProtocolException("ACTION_REJECTED", r.getMessage());
        return Map.of("accepted", true, "action", action, "message", r.getMessage());
    }

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
        throw new ProtocolException("UNKNOWN_PATH", "unsupported observation path: " + path);
    }

    private boolean evaluate(Map<String, Object> condition) {
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
    private static String response(String id, boolean ok, Object result, Object error) { Map<String, Object> m = new LinkedHashMap<String, Object>(); m.put("id", id); m.put("ok", ok); m.put("protocol_version", VERSION); m.put("result", ok ? result : null); if (!ok) m.put("error", error); return Json.write(m); }

    private static final class ProtocolException extends RuntimeException { final String code; ProtocolException(String c, String m) { super(m); code = c; } }

    /** Small dependency-free JSON codec for protocol envelopes. */
    static final class Json {
        static Object parse(String s) { return new Parser(s).parse(); }
        @SuppressWarnings("unchecked") static Map<String,Object> object(Object o) { if (!(o instanceof Map)) throw new ProtocolException("INVALID_JSON", "expected object"); return (Map<String,Object>) o; }
        static String string(Object o, String n) { if (!(o instanceof String) || ((String)o).isEmpty()) throw new ProtocolException("INVALID_ARGUMENT", n + " must be a non-empty string"); return (String)o; }
        static long longValue(Object o, String n) { if (!(o instanceof Number)) throw new ProtocolException("INVALID_ARGUMENT", n + " must be a number"); return ((Number)o).longValue(); }
        static String write(Object o) { if (o == null) return "null"; if (o instanceof String) return "\"" + ((String)o).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; if (o instanceof Boolean || o instanceof Number) return o.toString(); if (o instanceof Map) { StringBuilder b=new StringBuilder("{"); boolean first=true; for (Map.Entry<?,?> e: ((Map<?,?>)o).entrySet()) { if(!first)b.append(','); first=false; b.append(write(String.valueOf(e.getKey()))).append(':').append(write(e.getValue())); } return b.append('}').toString(); } if (o instanceof Iterable) { StringBuilder b=new StringBuilder("["); boolean first=true; for(Object v:(Iterable<?>)o){if(!first)b.append(',');first=false;b.append(write(v));} return b.append(']').toString(); } return write(String.valueOf(o)); }
        private static final class Parser { final String s; int p; Parser(String s){this.s=s;} Object parse(){skip(); Object v=value(); skip(); if(p!=s.length())bad(); return v;} Object value(){skip(); if(p>=s.length())bad(); char c=s.charAt(p); if(c=='{')return object(); if(c=='[')return array(); if(c=='\"')return string(); if(s.startsWith("true",p)){p+=4;return true;} if(s.startsWith("false",p)){p+=5;return false;} if(s.startsWith("null",p)){p+=4;return null;} return number();} Map<String,Object> object(){Map<String,Object> m=new LinkedHashMap<>();p++;skip();if(peek('}')){p++;return m;}while(true){String k=string();skip();expect(':');m.put(k,value());skip();if(peek('}')){p++;return m;}expect(',');} } List<Object> array(){List<Object> a=new ArrayList<>();p++;skip();if(peek(']')){p++;return a;}while(true){a.add(value());skip();if(peek(']')){p++;return a;}expect(',');}} String string(){expect('\"');StringBuilder b=new StringBuilder();while(p<s.length()&&s.charAt(p)!='\"'){char c=s.charAt(p++);if(c=='\\'){if(p>=s.length())bad();char e=s.charAt(p++);b.append(e=='n'?'\n':e);}else b.append(c);}expect('\"');return b.toString();} Number number(){int start=p;while(p<s.length()&&"-+.0123456789eE".indexOf(s.charAt(p))>=0)p++;try{return s.substring(start,p).contains(".")?Double.valueOf(s.substring(start,p)):Long.valueOf(s.substring(start,p));}catch(NumberFormatException e){bad();return 0;}}void skip(){while(p<s.length()&&Character.isWhitespace(s.charAt(p)))p++;}boolean peek(char c){return p<s.length()&&s.charAt(p)==c;}void expect(char c){skip();if(!peek(c))bad();p++;}void bad(){throw new ProtocolException("INVALID_JSON","invalid JSON near offset "+p);}}
    }
}
