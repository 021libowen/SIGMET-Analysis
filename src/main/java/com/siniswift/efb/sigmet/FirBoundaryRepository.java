package com.siniswift.efb.sigmet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

final class FirBoundaryRepository {
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final Map<String, Geometry> boundaries = new HashMap<>();
    private final Geometry world;

    FirBoundaryRepository() {
        world = polygon(new Coordinate[]{
                new Coordinate(-180, -90), new Coordinate(180, -90), new Coordinate(180, 90),
                new Coordinate(-180, 90), new Coordinate(-180, -90)
        });
        load();
    }

    Geometry find(String firId) {
        return boundaries.getOrDefault(firId, world);
    }

    boolean hasBoundary(String firId) {
        return boundaries.containsKey(firId);
    }

    private void load() {
        try (InputStream input = getClass().getResourceAsStream("/fir-boundaries.geojson")) {
            if (input == null) {
                return;
            }
            JsonNode root = new ObjectMapper().readTree(input);
            for (JsonNode feature : root.path("features")) {
                try {
                    JsonNode properties = feature.path("properties");
                    String firId = properties.path("firId").asText(null);
                    if (firId == null || firId.isEmpty()) {
                        firId = properties.path("id").asText(null);
                    }

                    if (firId == null || firId.isEmpty()) {
                        continue;
                    }
                    Geometry geometry = parseGeometry(feature.path("geometry"));
                    if (geometry != null) {
                        Geometry existing = boundaries.get(firId);
                        boundaries.put(firId, existing == null ? geometry : existing.union(geometry));
                    }
                } catch (Exception e) {
                    System.out.println("情报区feature: " + feature.toString()  +"转化错误：" + e.getMessage());
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Geometry parseGeometry(JsonNode geometry) {
        String type = geometry.path("type").asText();
        if ("Polygon".equals(type)) {
            return parsePolygon(geometry.path("coordinates"));
        }
        if ("MultiPolygon".equals(type)) {
            Geometry[] polygons = new Geometry[geometry.path("coordinates").size()];
            for (int i = 0; i < polygons.length; i++) {
                polygons[i] = parsePolygon(geometry.path("coordinates").get(i));
            }
            return geometryFactory.createGeometryCollection(polygons).union();
        }
        return null;
    }

    private Geometry parsePolygon(JsonNode coordinates) {
        JsonNode shell = coordinates.get(0);
        int size = shell.size();
        if (size == 0) {
            return null;
        }
        boolean closed = shell.get(0).get(0).asDouble() == shell.get(size - 1).get(0).asDouble()
                && shell.get(0).get(1).asDouble() == shell.get(size - 1).get(1).asDouble();
        Coordinate[] points = new Coordinate[closed ? size : size + 1];
        for (int i = 0; i < size; i++) {
            points[i] = new Coordinate(shell.get(i).get(0).asDouble(), shell.get(i).get(1).asDouble());
        }
        if (!closed) {
            points[size] = new Coordinate(points[0]);
        }
        return polygon(points);
    }

    private Geometry polygon(Coordinate[] coordinates) {
        LinearRing shell = geometryFactory.createLinearRing(coordinates);
        return geometryFactory.createPolygon(shell);
    }
}
