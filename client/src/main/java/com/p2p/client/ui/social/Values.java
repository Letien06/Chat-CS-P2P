package com.p2p.client.ui.social;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Small boundary helpers: the server remains authoritative for schema and permissions. */
final class Values {
    private Values() { }
    static Map<String,Object> map(Object value) {
        if (!(value instanceof Map<?,?> source)) return new LinkedHashMap<>();
        Map<String,Object> result = new LinkedHashMap<>();
        source.forEach((k,v) -> result.put(String.valueOf(k),v));
        return result;
    }
    static Map<String,Object> of(Object... pairs) {
        Map<String,Object> m = new LinkedHashMap<>();
        for (int i=0;i<pairs.length;i+=2) if(pairs[i+1]!=null) m.put(String.valueOf(pairs[i]),pairs[i+1]);
        return m;
    }
    static List<Map<String,Object>> rows(Object value) {
        if (!(value instanceof Collection<?> c)) return List.of();
        return c.stream().map(Values::map).toList();
    }
    static String str(Map<String,Object> m,String... keys) {
        for(String k:keys) if(m.get(k)!=null) return String.valueOf(m.get(k));
        return "";
    }
    static long num(Map<String,Object> m,String... keys) {
        for(String k:keys) { Object v=m.get(k); if(v instanceof Number n)return n.longValue();
            if(v!=null)try{return Long.parseLong(v.toString());}catch(NumberFormatException ignored){} }
        return 0;
    }
    static boolean bool(Map<String,Object> m,String key) { return Boolean.TRUE.equals(m.get(key)); }
    static String name(Map<String,Object> m) { String n=str(m,"displayName","title","username","name"); return n.isEmpty()?"Người dùng #"+num(m,"userId","id"):n; }
    static long id(Map<String,Object> m,String kind) { return num(m,kind+"Id","id"); }
    static String time(Map<String,Object> m) {
        Object v=m.get("createdAt"); if(v==null)v=m.get("serverTime");
        if(v==null)return "";
        try { Instant i=v instanceof Number n?Instant.ofEpochMilli(n.longValue()):Instant.parse(v.toString());
            return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(i);
        }catch(Exception e){return v.toString();}
    }
}
