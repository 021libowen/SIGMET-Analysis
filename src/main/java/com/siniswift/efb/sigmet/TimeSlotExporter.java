package com.siniswift.efb.sigmet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TimeSlotExporter {
    private static final DateTimeFormatter SLOT_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int SLOT_COUNT = 6;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 将特征列表按时隙分组并输出到指定文件 */
    public void export(LocalDateTime utcTime, List<SigmetFeature> features, Path outputPath) throws IOException {
        // 找到下一个整点时刻
        LocalDateTime slot = utcTime.withMinute(0).withSecond(0).withNano(0);
        if (!slot.isAfter(utcTime)) {
            slot = slot.plusHours(1);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < SLOT_COUNT; i++) {
            LocalDateTime slotTime = slot.plusHours(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("time", slotTime.format(SLOT_FMT));

            List<Map<String, Object>> data = new ArrayList<>();
            for (SigmetFeature f : features) {
                if (isValidAt(f, slotTime)) {
                    Map<String, Object> item = new LinkedHashMap<>(f.properties());
                    item.put("geojson", objectMapper.writeValueAsString(geometryToMap(f.geometry)));
                    data.add(item);
                }
            }
            entry.put("data", data);
            result.add(entry);
        }

        Files.createDirectories(outputPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), result);
    }

    /** 生效和失效都包含等于 */
    private static boolean isValidAt(SigmetFeature f, LocalDateTime time) {
        if (f.validTimeFrom == null || f.validTimeTo == null) return false;
        Instant instant = time.toInstant(ZoneOffset.UTC);
        return !instant.isBefore(f.validTimeFrom) && instant.isBefore(f.validTimeTo);
    }

    private Map<String, Object> geometryToMap(Geometry geometry) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (geometry instanceof MultiPolygon) {
            result.put("type", "MultiPolygon");
            List<Object> polygons = new ArrayList<>();
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                Polygon poly = (Polygon) geometry.getGeometryN(i);
                List<Object> rings = new ArrayList<>();
                rings.add(coordsToJson(poly.getExteriorRing().getCoordinates()));
                for (int j = 0; j < poly.getNumInteriorRing(); j++) {
                    rings.add(coordsToJson(poly.getInteriorRingN(j).getCoordinates()));
                }
                polygons.add(rings);
            }
            result.put("coordinates", polygons);
        } else {
            result.put("type", "Polygon");
            List<Object> rings = new ArrayList<>();
            rings.add(coordsToJson(geometry.getCoordinates()));
            result.put("coordinates", rings);
        }
        return result;
    }

    private List<List<Double>> coordsToJson(Coordinate[] coordinates) {
        List<List<Double>> ring = new ArrayList<>();
        for (Coordinate c : coordinates) {
            List<Double> point = new ArrayList<>();
            point.add(CoordinateParser.round3(c.x));
            point.add(CoordinateParser.round3(c.y));
            ring.add(point);
        }
        return ring;
    }

    /** 从 GeoJSON FeatureCollection 文件解析特征列表（供测试和外部调用） */
    static List<SigmetFeature> parseFeatures(Path geoJsonPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(geoJsonPath.toFile());
        JsonNode features = root.path("features");
        List<SigmetFeature> result = new ArrayList<>();
        GeometryFactory gf = new GeometryFactory();
        for (JsonNode f : features) {
            SigmetFeature sf = new SigmetFeature();
            JsonNode p = f.path("properties");
            sf.icaoId = textOrNull(p, "icaoId");
            sf.firId = textOrNull(p, "firId");
            sf.firName = textOrNull(p, "firName");
            sf.seriesId = textOrNull(p, "seriesId");
            String hazard = textOrNull(p, "hazard");
            if (hazard != null) sf.hazard = HazardType.valueOf(hazard);
            sf.qualifier = textOrNull(p, "qualifier");
            String from = textOrNull(p, "validTimeFrom");
            if (from != null) sf.validTimeFrom = Instant.parse(from.replace(".000Z", "Z"));
            String to = textOrNull(p, "validTimeTo");
            if (to != null) sf.validTimeTo = Instant.parse(to.replace(".000Z", "Z"));
            if (p.has("base")) sf.base = p.get("base").asInt();
            if (p.has("top")) sf.top = p.get("top").asInt();
            sf.dir = textOrNull(p, "dir");
            sf.spd = textOrNull(p, "spd");
            sf.chng = textOrNull(p, "chng");
            sf.rawSigmet = textOrNull(p, "rawSigmet");
            sf.geometry = parseGeometry(gf, f.path("geometry"));
            result.add(sf);
        }
        return result;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n != null && !n.isNull() ? n.asText() : null;
    }

    private static Geometry parseGeometry(GeometryFactory gf, JsonNode geom) {
        if (geom.isMissingNode()) return null;
        String type = geom.path("type").asText();
        JsonNode coords = geom.path("coordinates");
        try {
            if ("Polygon".equals(type)) {
                return parseJtsPolygon(gf, coords);
            }
            if ("MultiPolygon".equals(type)) {
                List<Polygon> parts = new ArrayList<>();
                for (JsonNode poly : coords) {
                    Polygon p = parseJtsPolygon(gf, poly);
                    if (p != null) parts.add(p);
                }
                if (parts.isEmpty()) return null;
                return gf.createMultiPolygon(parts.toArray(new Polygon[0]));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Polygon parseJtsPolygon(GeometryFactory gf, JsonNode rings) {
        if (!rings.isArray() || rings.size() == 0) return null;
        Coordinate[] shell = parseJtsRing(rings.get(0));
        if (shell == null || shell.length < 4) return null;
        if (rings.size() > 1) {
            org.locationtech.jts.geom.LinearRing[] holes = new org.locationtech.jts.geom.LinearRing[rings.size() - 1];
            for (int i = 1; i < rings.size(); i++) {
                Coordinate[] hole = parseJtsRing(rings.get(i));
                if (hole != null) holes[i - 1] = gf.createLinearRing(hole);
            }
            return gf.createPolygon(gf.createLinearRing(shell), holes);
        }
        return gf.createPolygon(shell);
    }

    private static Coordinate[] parseJtsRing(JsonNode ring) {
        if (!ring.isArray()) return null;
        Coordinate[] coords = new Coordinate[ring.size()];
        for (int i = 0; i < ring.size(); i++) {
            JsonNode pt = ring.get(i);
            coords[i] = new Coordinate(pt.get(0).asDouble(), pt.get(1).asDouble());
        }
        return coords;
    }
}
