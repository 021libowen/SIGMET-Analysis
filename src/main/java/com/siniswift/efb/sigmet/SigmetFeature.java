package com.siniswift.efb.sigmet;

import org.locationtech.jts.geom.Geometry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SigmetFeature {
    public String icaoId;
    public String firId;
    public String firName;
    public String seriesId;
    public HazardType hazard;
    public String qualifier;
    public Instant validTimeFrom;
    public Instant validTimeTo;
    public Integer base;
    public Integer top;
    public String dir;
    public String spd;
    public String chng;
    public String rawSigmet;
    public Geometry geometry;

    public Map<String, Object> properties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        put(properties, "icaoId", icaoId);
        put(properties, "firId", firId);
        put(properties, "firName", firName);
        put(properties, "seriesId", seriesId);
        put(properties, "hazard", hazard);
        put(properties, "qualifier", qualifier);
        if (validTimeFrom != null) {
            properties.put("validTimeFrom", validTimeFrom.toString().replace("Z", ".000Z"));
        }
        if (validTimeTo != null) {
            properties.put("validTimeTo", validTimeTo.toString().replace("Z", ".000Z"));
        }
        put(properties, "base", base);
        put(properties, "top", top);
        put(properties, "dir", dir);
        put(properties, "spd", spd);
        properties.put("chng", chng);
        put(properties, "rawSigmet", rawSigmet);
        return properties;
    }

    private static void put(Map<String, Object> properties, String key, Object value) {
        if (value != null) {
            properties.put(key, value);
        }
    }
}
