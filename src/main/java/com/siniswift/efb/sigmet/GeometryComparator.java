package com.siniswift.efb.sigmet;

import com.fasterxml.jackson.databind.JsonNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import java.util.*;

final class GeometryComparator {
    private static final double OVERLAP_THRESHOLD = 0.90;
    private static final GeometryFactory FACTORY = new GeometryFactory();

    private final Map<String, Map<String, JsonNode>> expectedMap;
    private final Map<String, Map<String, JsonNode>> actualMap;

    GeometryComparator(JsonNode expected, JsonNode actual) {
        this.expectedMap = buildKeyMap(expected);
        this.actualMap = buildKeyMap(actual);
    }

    /** 返回匹配率、不匹配的详细信息列表 */
    Result compare() {
        int total = 0;
        int matched = 0;
        List<Mismatch> mismatches = new ArrayList<>();
        for (Map.Entry<String, Map<String, JsonNode>> entry : expectedMap.entrySet()) {
            String key = entry.getKey();
            Map<String, JsonNode> actualProps = actualMap.get(key);
            if (actualProps == null) continue;
            total++;
            Map<String, JsonNode> expectedProps = entry.getValue();
            Geometry expectedGeom = parseGeometry(expectedProps.get("geometry"));
            Geometry actualGeom = parseGeometry(actualProps.get("geometry"));
            if (expectedGeom == null || actualGeom == null) {
                mismatches.add(new Mismatch(key, expectedProps, actualProps, 0));
                continue;
            }
            double expectedArea = expectedGeom.getArea();
            double actualArea = actualGeom.getArea();
            double ratio = overlapRatio(expectedGeom, actualGeom);
            if (ratio >= OVERLAP_THRESHOLD
                    || expectedArea == 0
                    || (expectedArea < 1 && actualArea < 1)) {
                matched++;
            } else {
                mismatches.add(new Mismatch(key, expectedProps, actualProps, expectedType(expectedGeom), expectedArea,
                        expectedType(actualGeom), actualArea, ratio));
            }
        }
        return new Result(total, matched, mismatches);
    }

    private static double overlapRatio(Geometry a, Geometry b) {
        try {
            Geometry intersection = a.intersection(b);
            double overlap = intersection.getArea();
            double minArea = Math.min(a.getArea(), b.getArea());
            if (minArea == 0) {
                return overlap > 0 ? 1.0 : 0.0;
            }
            return overlap / minArea;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static Geometry parseGeometry(JsonNode geomNode) {
        if (geomNode == null || geomNode.isNull()) return null;
        String type = geomNode.path("type").asText();
        JsonNode coords = geomNode.path("coordinates");
        if (coords.isMissingNode()) return null;
        try {
            switch (type) {
                case "Polygon":
                    return parsePolygon(coords);
                case "MultiPolygon":
                    return parseMultiPolygon(coords);
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Polygon parsePolygon(JsonNode rings) {
        Coordinate[] shell = parseRing(rings.get(0));
        if (shell == null || shell.length < 4) return null;
        Polygon poly;
        if (rings.size() > 1) {
            org.locationtech.jts.geom.LinearRing[] holes = new org.locationtech.jts.geom.LinearRing[rings.size() - 1];
            for (int i = 1; i < rings.size(); i++) {
                Coordinate[] hole = parseRing(rings.get(i));
                if (hole != null) {
                    holes[i - 1] = FACTORY.createLinearRing(hole);
                }
            }
            poly = FACTORY.createPolygon(FACTORY.createLinearRing(shell), holes);
        } else {
            poly = FACTORY.createPolygon(shell);
        }
        // 使用 buffer(0) 修复自交等拓扑问题
        return (Polygon) poly.buffer(0);
    }

    private static Geometry parseMultiPolygon(JsonNode polygons) {
        List<Polygon> parts = new ArrayList<>();
        for (JsonNode poly : polygons) {
            Polygon p = parsePolygon(poly);
            if (p != null && !p.isEmpty()) {
                parts.add(p);
            }
        }
        if (parts.isEmpty()) return null;
        if (parts.size() == 1) return parts.get(0);
        return FACTORY.createMultiPolygon(parts.toArray(new Polygon[0]));
    }

    private static Coordinate[] parseRing(JsonNode ring) {
        if (ring == null || !ring.isArray()) return null;
        List<Coordinate> coords = new ArrayList<>();
        for (JsonNode point : ring) {
            if (point.isArray() && point.size() >= 2) {
                double x = point.get(0).asDouble();
                double y = point.get(1).asDouble();
                coords.add(new Coordinate(x, y));
            }
        }
        return coords.toArray(new Coordinate[0]);
    }

    private static Map<String, Map<String, JsonNode>> buildKeyMap(JsonNode fc) {
        Map<String, Map<String, JsonNode>> map = new LinkedHashMap<>();
        JsonNode features = fc.path("features");
        for (JsonNode f : features) {
            JsonNode props = f.path("properties");
            String key = buildKey(props);
            if (key == null) continue;
            Map<String, JsonNode> propMap = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = props.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                propMap.put(e.getKey(), e.getValue());
            }
            propMap.put("geometry", f.path("geometry"));
            map.put(key, propMap);
        }
        return map;
    }

    private static String buildKey(JsonNode props) {
        String icaoId = textOrNull(props, "icaoId");
        String firId = textOrNull(props, "firId");
        String seriesId = textOrNull(props, "seriesId");
        String from = textOrNull(props, "validTimeFrom");
        String to = textOrNull(props, "validTimeTo");
        if (icaoId == null && firId == null && seriesId == null && from == null && to == null) return null;
        return String.join("|",
                icaoId != null ? icaoId : "",
                firId != null ? firId : "",
                seriesId != null ? seriesId : "",
                from != null ? from : "",
                to != null ? to : "");
    }

    private static String textOrNull(JsonNode props, String name) {
        JsonNode node = props.get(name);
        return node != null && !node.isNull() ? node.asText() : null;
    }

    static final class Mismatch {
        final String key;
        final String rawSigmet;
        final String expectedType;
        final double expectedArea;
        final String actualType;
        final double actualArea;
        final double overlapRatio;

        Mismatch(String key, Map<String, JsonNode> expectedProps, Map<String, JsonNode> actualProps, double overlapRatio) {
            this(key, expectedProps, actualProps,
                    geomInfo(expectedProps.get("geometry")), geomArea(expectedProps.get("geometry")),
                    geomInfo(actualProps.get("geometry")), geomArea(actualProps.get("geometry")),
                    overlapRatio);
        }

        Mismatch(String key, Map<String, JsonNode> expectedProps, Map<String, JsonNode> actualProps,
                 String expectedType, double expectedArea, String actualType, double actualArea, double overlapRatio) {
            this.key = key;
            String raw = textOrNull(expectedProps, "rawSigmet");
            this.rawSigmet = raw != null ? raw : textOrNull(actualProps, "rawSigmet");
            this.expectedType = expectedType;
            this.expectedArea = expectedArea;
            this.actualType = actualType;
            this.actualArea = actualArea;
            this.overlapRatio = overlapRatio;
        }
    }

    private static String geomInfo(JsonNode geom) {
        if (geom == null || geom.isNull()) return "null";
        return geom.path("type").asText("unknown");
    }

    private static String expectedType(Geometry g) {
        return g != null ? g.getGeometryType() : "null";
    }

    private static double geomArea(JsonNode geom) {
        Geometry g = parseGeometry(geom);
        return g != null ? g.getArea() : 0;
    }

    private static String textOrNull(Map<String, JsonNode> props, String name) {
        JsonNode node = props.get(name);
        return node != null && !node.isNull() ? node.asText() : null;
    }

    static final class Result {
        final int total;
        final int matched;
        final List<Mismatch> mismatches;

        Result(int total, int matched, List<Mismatch> mismatches) {
            this.total = total;
            this.matched = matched;
            this.mismatches = mismatches;
        }

        double rate() {
            return total == 0 ? 100.0 : 100.0 * matched / total;
        }
    }
}
