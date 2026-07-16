package com.weather.sigmet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class GeoJsonComparator {
    private final ObjectMapper objectMapper = new ObjectMapper();

    Result compare(String expectedJson, String actualJson) throws Exception {
        JsonNode expected = objectMapper.readTree(expectedJson);
        JsonNode actual = objectMapper.readTree(actualJson);
        List<String> differences = new ArrayList<>();
        JsonNode expectedFeatures = expected.path("features");
        JsonNode actualFeatures = actual.path("features");
        if (expectedFeatures.size() != actualFeatures.size()) {
            differences.add("feature count expected=" + expectedFeatures.size() + " actual=" + actualFeatures.size());
        }
        int count = Math.min(expectedFeatures.size(), actualFeatures.size());
        for (int i = 0; i < count; i++) {
            compareProperties(i, expectedFeatures.get(i).path("properties"), actualFeatures.get(i).path("properties"), differences);
            compareGeometry(i, expectedFeatures.get(i).path("geometry"), actualFeatures.get(i).path("geometry"), differences);
        }
        return new Result(differences.isEmpty(), differences);
    }

    private void compareProperties(int index, JsonNode expected, JsonNode actual, List<String> differences) {
        Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode actualValue = actual.get(field.getKey());
            if (actualValue == null || !field.getValue().equals(actualValue)) {
                differences.add("feature " + index + " property " + field.getKey() + " expected=" + field.getValue() + " actual=" + actualValue);
                if (differences.size() > 50) {
                    return;
                }
            }
        }
    }

    private void compareGeometry(int index, JsonNode expected, JsonNode actual, List<String> differences) {
        if (!expected.path("type").asText().equals(actual.path("type").asText())) {
            differences.add("feature " + index + " geometry type expected=" + expected.path("type").asText() + " actual=" + actual.path("type").asText());
            return;
        }
        JsonNode expectedRing = expected.path("coordinates").path(0);
        JsonNode actualRing = actual.path("coordinates").path(0);
        double[] expectedBox = bounds(expectedRing);
        double[] actualBox = bounds(actualRing);
        double expectedArea = Math.abs(area(expectedRing));
        double actualArea = Math.abs(area(actualRing));
        double boxDelta = 0.0;
        for (int i = 0; i < expectedBox.length; i++) {
            boxDelta = Math.max(boxDelta, Math.abs(expectedBox[i] - actualBox[i]));
        }
        double areaDelta = expectedArea == 0.0 ? Math.abs(actualArea) : Math.abs(expectedArea - actualArea) / expectedArea;
        if (boxDelta > 0.05 || areaDelta > 0.10) {
            differences.add("feature " + index + " geometry differs boxDelta=" + boxDelta + " areaDelta=" + areaDelta
                    + " expectedPoints=" + expectedRing.size() + " actualPoints=" + actualRing.size());
        }
    }

    private double[] bounds(JsonNode ring) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (JsonNode point : ring) {
            double x = point.get(0).asDouble();
            double y = point.get(1).asDouble();
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    private double area(JsonNode ring) {
        if (ring.size() < 3) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < ring.size(); i++) {
            JsonNode a = ring.get(i);
            JsonNode b = ring.get((i + 1) % ring.size());
            sum += a.get(0).asDouble() * b.get(1).asDouble() - b.get(0).asDouble() * a.get(1).asDouble();
        }
        return sum / 2.0;
    }

    static final class Result {
        private final boolean passed;
        private final List<String> differences;

        Result(boolean passed, List<String> differences) {
            this.passed = passed;
            this.differences = differences;
        }

        boolean passed() {
            return passed;
        }

        List<String> differences() {
            return differences;
        }
    }
}
