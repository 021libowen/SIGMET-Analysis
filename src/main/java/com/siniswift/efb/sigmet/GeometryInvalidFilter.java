package com.siniswift.efb.sigmet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 遍历 data 下所有 sigmet-geojson.txt，修复并输出规范的 geometry 到 sigmet-geometry-except.txt。
 * 修复包括：闭合未闭合的环、buffer(0) 修复轻微拓扑问题。
 * 无法修复的 feature 会被过滤掉。
 */
public final class GeometryInvalidFilter {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path dataDir = args.length == 0 ? Paths.get("data") : Paths.get(args[0]);
        List<Path> directories;
        try (Stream<Path> stream = Files.list(dataDir)) {
            directories = stream.filter(Files::isDirectory).sorted().collect(Collectors.toList());
        }
        int totalFixed = 0;
        int totalFiltered = 0;
        int totalDirs = 0;
        for (Path dir : directories) {
            Path geoJsonPath = dir.resolve("sigmet-geojson.txt");
            if (!Files.exists(geoJsonPath)) continue;
            JsonNode root = MAPPER.readTree(geoJsonPath.toFile());
            JsonNode features = root.path("features");
            List<JsonNode> outFeatures = new ArrayList<>();
            int filtered = 0;
            for (JsonNode feature : features) {
                ObjectNode fixed = fixFeature(feature);
                if (fixed != null) {
                    outFeatures.add(fixed);
                } else {
                    filtered++;
                }
            }
            Path exceptPath = dir.resolve("sigmet-geometry-except.txt");
            if (!outFeatures.isEmpty()) {
                totalFixed += outFeatures.size();
                totalDirs++;
                totalFiltered += filtered;
                ObjectNode out = MAPPER.createObjectNode();
                out.put("type", "FeatureCollection");
                ArrayNode arr = out.putArray("features");
                for (JsonNode f : outFeatures) arr.add(f);
                Files.write(exceptPath, MAPPER.writeValueAsString(out).getBytes(StandardCharsets.UTF_8));
            } else {
                Files.deleteIfExists(exceptPath);
            }
        }
        System.out.println("扫描目录数: " + directories.size());
        System.out.println("输出目录数: " + totalDirs);
        System.out.println("修复保留的 feature 总数: " + totalFixed);
        System.out.println("无法修复过滤掉的总数: " + totalFiltered);
    }

    private static final GeometryFactory FACTORY = new GeometryFactory();

    /** 尝试解析并修复 geometry，成功则返回修复后的 feature，失败返回 null */
    private static ObjectNode fixFeature(JsonNode feature) {
        JsonNode geomNode = feature.path("geometry");
        Geometry g = toJtsGeometry(geomNode);
        if (g == null) return null;
        // 尝试修复
        if (!g.isValid()) {
            g = fixGeometry(g);
            if (g == null || g.isEmpty()) return null;
        }
        // 转换回 GeoJSON（所有环自动闭合）
        JsonNode fixedGeom = toGeoJsonGeometry(g);
        if (fixedGeom == null) return null;
        ObjectNode result = feature.deepCopy();
        result.set("geometry", fixedGeom);
        return result;
    }

    /** 尝试 buffer(0) 修复，失败返回 null */
    private static Geometry fixGeometry(Geometry g) {
        try {
            Geometry fixed = g.buffer(0);
            if (!fixed.isEmpty() && fixed.isValid()) return fixed;
        } catch (Exception ignored) {}
        return null;
    }

    private static Geometry toJtsGeometry(JsonNode geom) {
        String type = geom.path("type").asText();
        JsonNode coords = geom.path("coordinates");
        if (coords.isMissingNode()) return null;
        try {
            switch (type) {
                case "Polygon":     return toJtsPolygon(coords);
                case "MultiPolygon": return toJtsMultiPolygon(coords);
                default: return null;
            }
        } catch (Exception e) {
            // 解析失败，尝试不闭合环的方式
            return null;
        }
    }

    private static Polygon toJtsPolygon(JsonNode rings) {
        LinearRing shell = toJtsRing(rings.get(0));
        if (shell == null) return null;
        List<LinearRing> holeList = new ArrayList<>();
        for (int i = 1; i < rings.size(); i++) {
            LinearRing hole = toJtsRing(rings.get(i));
            if (hole != null) holeList.add(hole);
        }
        LinearRing[] holes = holeList.isEmpty() ? null : holeList.toArray(new LinearRing[0]);
        return FACTORY.createPolygon(shell, holes);
    }

    private static Geometry toJtsMultiPolygon(JsonNode polygons) {
        List<Polygon> parts = new ArrayList<>();
        for (JsonNode poly : polygons) {
            Polygon p = toJtsPolygon(poly);
            if (p != null) parts.add(p);
        }
        if (parts.isEmpty()) return null;
        if (parts.size() == 1) return parts.get(0);
        return FACTORY.createMultiPolygon(parts.toArray(new Polygon[0]));
    }

    private static LinearRing toJtsRing(JsonNode ring) {
        if (ring == null || !ring.isArray() || ring.size() < 3) return null;
        List<Coordinate> coords = new ArrayList<>();
        for (JsonNode pt : ring) {
            Coordinate c = readCoord(pt);
            if (c == null) return null;
            // 跳过连续重复点
            if (!coords.isEmpty() && c.equals2D(coords.get(coords.size() - 1))) continue;
            coords.add(c);
        }
        if (coords.size() < 3) return null;
        // 确保闭合
        Coordinate first = coords.get(0);
        Coordinate last = coords.get(coords.size() - 1);
        if (!first.equals2D(last)) {
            coords.add(new Coordinate(first));
        }
        return FACTORY.createLinearRing(coords.toArray(new Coordinate[0]));
    }

    /** JTS Geometry → GeoJSON JsonNode，保证环闭合 */
    private static JsonNode toGeoJsonGeometry(Geometry g) {
        if (g instanceof Polygon) {
            return toGeoJsonPolygon((Polygon) g);
        }
        if (g instanceof org.locationtech.jts.geom.MultiPolygon) {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("type", "MultiPolygon");
            ArrayNode polys = result.putArray("coordinates");
            for (int i = 0; i < g.getNumGeometries(); i++) {
                polys.add(toGeoJsonPolyCoords((Polygon) g.getGeometryN(i)));
            }
            return result;
        }
        return null;
    }

    private static ObjectNode toGeoJsonPolygon(Polygon p) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("type", "Polygon");
        result.set("coordinates", toGeoJsonPolyCoords(p));
        return result;
    }

    private static ArrayNode toGeoJsonPolyCoords(Polygon p) {
        ArrayNode rings = MAPPER.createArrayNode();
        rings.add(toGeoJsonRing(p.getExteriorRing()));
        for (int i = 0; i < p.getNumInteriorRing(); i++) {
            rings.add(toGeoJsonRing(p.getInteriorRingN(i)));
        }
        return rings;
    }

    private static ArrayNode toGeoJsonRing(org.locationtech.jts.geom.LineString ring) {
        ArrayNode arr = MAPPER.createArrayNode();
        Coordinate[] coords = ring.getCoordinates();
        for (Coordinate c : coords) {
            ArrayNode pt = MAPPER.createArrayNode();
            pt.add(round6(c.x));
            pt.add(round6(c.y));
            arr.add(pt);
        }
        return arr;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    private static Coordinate readCoord(JsonNode pt) {
        if (pt == null || !pt.isArray() || pt.size() < 2) return null;
        return new Coordinate(pt.get(0).asDouble(), pt.get(1).asDouble());
    }
}
