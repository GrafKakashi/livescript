package de.grafkakashi.livescript.api;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes "object" arguments coming from either JS (Rhino Scriptable) or
 * Lua (LuaJ LuaTable) into a uniform map-like read API. Plain Java Maps and
 * Lists are supported too, for tests and direct use from Java callsites.
 *
 * Design note: we explicitly do NOT convert eagerly into a HashMap, because:
 *   - JS objects can have inherited prototypes we'd duplicate
 *   - Lua tables can be large arrays, materializing is wasteful for the
 *     common "I only need three keys" case
 * Instead, each {@link #get(String)} call resolves through the native type.
 *
 * Conversion rules for values:
 *   - Strings stay strings.
 *   - Numbers become Double.
 *   - Booleans stay booleans.
 *   - JS arrays become List&lt;Object&gt;.
 *   - Lua sequential tables (1..N keys) become List&lt;Object&gt;.
 *   - Nested objects become wrapped SpecAdapters when fetched via getMap/get.
 */
public final class SpecAdapter {
    private final Object backing;

    private SpecAdapter(Object backing) { this.backing = backing; }

    public static SpecAdapter wrap(Object o) {
        if (o instanceof NativeJavaObject njo) o = njo.unwrap();
        return new SpecAdapter(o);
    }

    public boolean has(String key) {
        Object v = get(key);
        return v != null;
    }

    /**
     * Raw fetch. Returns null if the key is missing. Numbers come out as Double,
     * strings as String, nested objects as their native type (Scriptable / LuaValue / Map).
     */
    public Object get(String key) {
        if (backing instanceof Map<?, ?> m) {
            return convertJava(m.get(key));
        }
        if (backing instanceof ScriptableObject so) {
            Object v = ScriptableObject.getProperty(so, key);
            if (v == ScriptableObject.NOT_FOUND || v instanceof Undefined) return null;
            return convertJs(v);
        }
        if (backing instanceof org.mozilla.javascript.Scriptable s) {
            Object v = s.get(key, s);
            if (v == org.mozilla.javascript.Scriptable.NOT_FOUND || v instanceof Undefined) return null;
            return convertJs(v);
        }
        if (backing instanceof LuaTable lt) {
            LuaValue v = lt.get(key);
            if (v == null || v.isnil()) return null;
            return convertLua(v);
        }
        return null;
    }

    public String getString(String key, String defaultValue) {
        Object v = get(key);
        return v == null ? defaultValue : v.toString();
    }

    public String getString(String key) {
        return getString(key, null);
    }

    public double getDouble(String key, double defaultValue) {
        Object v = get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        return defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object v = get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) {
            String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (t.equals("true") || t.equals("yes") || t.equals("1")) return true;
            if (t.equals("false") || t.equals("no") || t.equals("0")) return false;
        }
        return defaultValue;
    }

    public List<String> getStringList(String key) {
        Object v = get(key);
        if (!(v instanceof List<?> raw)) return null;
        List<String> out = new ArrayList<>(raw.size());
        for (Object o : raw) out.add(o == null ? null : o.toString());
        return out;
    }

    public List<Object> getList(String key) {
        Object v = get(key);
        if (!(v instanceof List<?> raw)) return null;
        return new ArrayList<>(raw);
    }

    /**
     * Read a nested map as plain Java entries. Used for recipe.key{} where the
     * caller needs to iterate ALL char-keys without knowing them in advance.
     */
    public Map<String, Object> getMap(String key) {
        Object v = get(key);
        return materializeMap(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> materializeMap(Object v) {
        if (v == null) return null;
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        if (v instanceof ScriptableObject so) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Object id : so.getIds()) {
                if (id instanceof String name) {
                    Object val = ScriptableObject.getProperty(so, name);
                    if (val instanceof Undefined || val == ScriptableObject.NOT_FOUND) continue;
                    out.put(name, convertJs(val));
                }
            }
            return out;
        }
        if (v instanceof org.mozilla.javascript.Scriptable s) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Object id : s.getIds()) {
                if (id instanceof String name) {
                    Object val = s.get(name, s);
                    if (val instanceof Undefined || val == org.mozilla.javascript.Scriptable.NOT_FOUND) continue;
                    out.put(name, convertJs(val));
                }
            }
            return out;
        }
        if (v instanceof LuaTable lt) {
            Map<String, Object> out = new LinkedHashMap<>();
            LuaValue k = LuaValue.NIL;
            while (true) {
                var next = lt.next(k);
                k = next.arg(1);
                if (k.isnil()) break;
                LuaValue val = next.arg(2);
                out.put(k.tojstring(), convertLua(val));
            }
            return out;
        }
        return null;
    }

    private static Object convertJava(Object v) {
        if (v == null) return null;
        if (v instanceof List<?>) return v;
        if (v instanceof Map<?, ?>) return v;
        return v;
    }

    private static Object convertJs(Object v) {
        if (v == null || v instanceof Undefined) return null;
        if (v instanceof org.mozilla.javascript.NativeArray arr) {
            List<Object> out = new ArrayList<>((int) arr.getLength());
            for (int i = 0; i < arr.getLength(); i++) {
                Object e = arr.get(i, arr);
                out.add(convertJs(e));
            }
            return out;
        }
        if (v instanceof org.mozilla.javascript.NativeJavaObject njo) return njo.unwrap();
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof CharSequence || v instanceof Boolean) return v;
        return v; // includes nested Scriptables — recipient calls SpecAdapter.wrap on it
    }

    private static Object convertLua(LuaValue v) {
        if (v == null || v.isnil()) return null;
        if (v.isstring() && !v.isnumber()) return v.tojstring();
        if (v.isnumber()) return v.todouble();
        if (v.isboolean()) return v.toboolean();
        if (v.istable()) {
            LuaTable t = v.checktable();
            // Detect sequential 1..N table → list
            int n = t.length();
            if (n > 0) {
                List<Object> list = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) list.add(convertLua(t.get(i)));
                return list;
            }
            // Non-sequential → map (returned raw; SpecAdapter handles iteration)
            return t;
        }
        if (v.isuserdata()) return v.touserdata();
        return v.tojstring();
    }
}
