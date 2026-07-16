package com.weather.sigmet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public final class GeoJsonWriter {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleDateFormat dateFormat;

    public GeoJsonWriter() {
        dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'.000Z'");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public String write(List<SigmetFeature> features) throws Exception {
        return objectMapper.writeValueAsString(toFeatureCollection(features));
    }

    public JsonNode toJson(List<SigmetFeature> features) {
        return objectMapper.valueToTree(toFeatureCollection(features));
    }

    private Map<String, Object> toFeatureCollection(List<SigmetFeature> features) {
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("type", "FeatureCollection");
        List<Object> jsonFeatures = new ArrayList<>();
        for (SigmetFeature f : features) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "Feature");
            item.put("properties", toFlatMap(f));
            item.put("geometry", parseGeometryString(f.geojson));
            jsonFeatures.add(item);
        }
        collection.put("features", jsonFeatures);
        return collection;
    }

    /** Geometry → GeoJSON Map */
    public Map<String, Object> geometryToMap(Geometry geometry) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (geometry instanceof MultiPolygon) {
            result.put("type", "MultiPolygon");
            List<Object> polygons = new ArrayList<>();
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                Polygon poly = (Polygon) geometry.getGeometryN(i);
                List<Object> rings = new ArrayList<>();
                rings.add(ring(poly.getExteriorRing().getCoordinates()));
                for (int j = 0; j < poly.getNumInteriorRing(); j++) {
                    rings.add(ring(poly.getInteriorRingN(j).getCoordinates()));
                }
                polygons.add(rings);
            }
            result.put("coordinates", polygons);
        } else {
            result.put("type", "Polygon");
            List<Object> rings = new ArrayList<>();
            rings.add(ring(geometry.getCoordinates()));
            result.put("coordinates", rings);
        }
        return result;
    }

    /** Geometry → GeoJSON 字符串 */
    public String geometryToJson(Geometry geometry) throws Exception {
        return objectMapper.writeValueAsString(geometryToMap(geometry));
    }

    /** 字段即最终输出格式，直接装 Map，跳过 JTS Geometry */
    public Map<String, Object> toFlatMap(SigmetFeature f) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "icaoId", f.icaoId);
        put(map, "firId", f.firId);
        put(map, "firName", f.firName);
        put(map, "seriesId", f.seriesId);
        put(map, "hazard", f.hazard);
        put(map, "qualifier", f.qualifier);
        put(map, "validTimeFrom", fmt(f.validTimeFrom));
        put(map, "validTimeTo", fmt(f.validTimeTo));
        put(map, "issued", fmt(f.issued));
        put(map, "category", f.category);
        put(map, "base", f.base);
        put(map, "top", f.top);
        put(map, "dir", f.dir);
        put(map, "spd", f.spd);
        map.put("chng", f.chng);
        put(map, "rawSigmet", f.rawSigmet);
        map.put("geojson", f.geojson);
        return map;
    }

    /** 单条 Feature 转扁平 JSON 字符串 */
    public String writeFlat(SigmetFeature feature) throws Exception {
        return objectMapper.writeValueAsString(toFlatMap(feature));
    }

    private String fmt(Date d) {
        if (d == null) return null;
        return dateFormat.format(d);
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseGeometryString(String geojson) {
        if (geojson == null || geojson.isEmpty()) return null;
        try {
            return objectMapper.readValue(geojson, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private List<List<Double>> ring(Coordinate[] coordinates) {
        List<List<Double>> ring = new ArrayList<>();
        for (Coordinate coordinate : coordinates) {
            List<Double> point = new ArrayList<>();
            point.add(clampLon(CoordinateParser.round3(coordinate.x)));
            point.add(CoordinateParser.round3(coordinate.y));
            ring.add(point);
        }
        // GeoJSON 规范：环至少 4 个点且首尾相同
        if (ring.size() < 4) {
            while (ring.size() < 4) {
                ring.add(ring.get(0));
            }
        }
        if (!ring.get(0).equals(ring.get(ring.size() - 1))) {
            ring.add(new ArrayList<>(ring.get(0)));
        }
        return ring;
    }

    /** 避免恰好 180/-180 导致渲染器环绕，向内缩 0.001° */
    private static double clampLon(double lon) {
        if (lon >= 180.0) return 179.999;
        if (lon <= -180.0) return -179.999;
        return lon;
    }
}
