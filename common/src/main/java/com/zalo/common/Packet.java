package com.zalo.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Gói tin JSON: bọc JsonObject, luôn có trường "type". */
public final class Packet {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final JsonObject obj;

    private Packet(JsonObject obj) {
        this.obj = obj;
    }

    public static Packet of(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return new Packet(o);
    }

    public static Packet parse(String json) {
        JsonElement e = JsonParser.parseString(json);
        if (!e.isJsonObject()) throw new IllegalArgumentException("Packet must be a JSON object");
        return new Packet(e.getAsJsonObject());
    }

    public String type() { return str("type"); }

    // ---------- ghi ----------
    public Packet put(String k, String v) { obj.addProperty(k, v); return this; }
    public Packet put(String k, Number v) { obj.addProperty(k, v); return this; }
    public Packet put(String k, Boolean v) { obj.addProperty(k, v); return this; }
    public Packet put(String k, JsonElement v) { obj.add(k, v); return this; }

    // ---------- đọc (null-safe) ----------
    private JsonElement get(String k) {
        JsonElement e = obj.get(k);
        return (e == null || e instanceof JsonNull) ? null : e;
    }

    public boolean has(String k) { return get(k) != null; }
    public String str(String k) { JsonElement e = get(k); return e == null ? null : e.getAsString(); }
    public int intVal(String k, int def) { JsonElement e = get(k); return e == null ? def : e.getAsInt(); }
    public long longVal(String k, long def) { JsonElement e = get(k); return e == null ? def : e.getAsLong(); }
    public boolean boolVal(String k, boolean def) { JsonElement e = get(k); return e == null ? def : e.getAsBoolean(); }
    public JsonArray array(String k) { JsonElement e = get(k); return e != null && e.isJsonArray() ? e.getAsJsonArray() : null; }
    public JsonObject object(String k) { JsonElement e = get(k); return e != null && e.isJsonObject() ? e.getAsJsonObject() : null; }

    public JsonObject raw() { return obj; }
    public String toJson() { return GSON.toJson(obj); }
    @Override public String toString() { return toJson(); }
}
