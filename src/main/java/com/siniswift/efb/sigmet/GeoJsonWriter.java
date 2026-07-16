package com.siniswift.efb.sigmet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GeoJsonWriter {
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        for (SigmetFeature feature : features) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "Feature");
            item.put("properties", feature.properties());
            item.put("geometry", geometry(feature.geometry));
            jsonFeatures.add(item);
        }
        collection.put("features", jsonFeatures);
        return collection;
    }

    private Map<String, Object> geometry(Geometry geometry) {
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

    private List<List<Double>> ring(Coordinate[] coordinates) {
        List<List<Double>> ring = new ArrayList<>();
        for (Coordinate coordinate : coordinates) {
            List<Double> point = new ArrayList<>();
            point.add(CoordinateParser.round3(coordinate.x));
            point.add(CoordinateParser.round3(coordinate.y));
            ring.add(point);
        }
        return ring;
    }
}
