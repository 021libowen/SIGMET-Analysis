package com.weather.sigmet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.NullNode;

public final class Main {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 不参与比较的属性 */
    private static final Set<String> IGNORED_PROPS = new HashSet<>(Arrays.asList("firName", "rawSigmet"));

    /** 若官方（expected）值为 null，则跳过比较的属性 */
    private static final Set<String> SKIP_IF_EXPECTED_NULL = new HashSet<>(Arrays.asList("dir", "spd", "chng"));

    public static void main(String[] args) throws Exception {
        Path dataDir = args.length == 0 ? Paths.get("data") : Paths.get(args[0]);
        SigmetParser parser = new SigmetParser();
        GeoJsonWriter writer = new GeoJsonWriter();

        // ===== Phase 1: 批量解析所有 sigmet.txt → sigmet-geojson-actual.txt =====
        System.out.println("===== Phase 1: 批量解析并保存 =====");
        List<Path> directories;
        try (Stream<Path> stream = Files.list(dataDir)) {
            directories = stream.filter(Files::isDirectory).sorted().collect(Collectors.toList());
        }
        int converted = 0;
        int failed = 0;
        List<String[]> failedList = new ArrayList<>();
        for (Path directory : directories) {
            Path sigmetFile = directory.resolve("sigmet.txt");
            if (!Files.exists(sigmetFile)) {
                continue;
            }
            try {
                String sigmetText = new String(Files.readAllBytes(sigmetFile), StandardCharsets.UTF_8);
                String actual = writer.write(parser.parse(sigmetText, directory.getFileName().toString()));
                Files.write(directory.resolve("sigmet-geojson-actual.txt"), actual.getBytes(StandardCharsets.UTF_8));
                converted++;
            } catch (Exception e) {
                failed++;
                String sigmetText = new String(Files.readAllBytes(sigmetFile), StandardCharsets.UTF_8);
                failedList.add(new String[]{directory.getFileName().toString(), sigmetText, e.getMessage()});
                System.out.println("  FAILED: " + directory.getFileName() + " - " + e.getMessage());
            }
        }
        System.out.println("  转换完成: " + converted + " 成功, " + failed + " 失败\n");

        // 写解析失败报告
        if (!failedList.isEmpty()) {
            Path failedReportPath = dataDir.resolve("../parse-error-report.txt");
            StringBuilder failedReport = new StringBuilder();
            failedReport.append("解析失败列表\n");
            failedReport.append("============\n\n");
            for (String[] item : failedList) {
                failedReport.append("--- ").append(item[0]).append(" ---\n");
                failedReport.append("错误: ").append(item[2]).append("\n");
                failedReport.append("原始报文:\n").append(item[1]).append("\n\n");
            }
            try (BufferedWriter bw = Files.newBufferedWriter(failedReportPath, StandardCharsets.UTF_8)) {
                bw.write(failedReport.toString());
            }
            System.out.println("  失败报告已写入: " + failedReportPath.toAbsolutePath() + "\n");
        }

        // ===== Phase 2: 按 Key 比较 =====
        System.out.println("===== Phase 2: 按 Key 比较属性 =====");
        int totalKeys = 0;
        int totalProps = 0;
        int matchedProps = 0;
        Map<String, Integer> diffByProperty = new LinkedHashMap<>();
        StringBuilder diffReport = new StringBuilder();
        diffReport.append("SIGMET GeoJSON 比较差异报告\n");
        diffReport.append("=========================\n\n");

        for (Path directory : directories) {
            Path expectedPath = directory.resolve("sigmet-geojson.txt");
            Path actualPath = directory.resolve("sigmet-geojson-actual.txt");
            if (!Files.exists(expectedPath) || !Files.exists(actualPath)) {
                continue;
            }
            JsonNode expectedJson = OBJECT_MAPPER.readTree(expectedPath.toFile());
            JsonNode actualJson = OBJECT_MAPPER.readTree(actualPath.toFile());

            Map<String, Map<String, JsonNode>> expectedMap = buildKeyMap(expectedJson);
            Map<String, Map<String, JsonNode>> actualMap = buildKeyMap(actualJson);

            for (Map.Entry<String, Map<String, JsonNode>> entry : expectedMap.entrySet()) {
                String key = entry.getKey();
                Map<String, JsonNode> actualProps = actualMap.get(key);
                if (actualProps == null) {
                    continue;
                }
                totalKeys++;
                Map<String, JsonNode> expectedProps = entry.getValue();
                List<String> keyDiffs = new ArrayList<>();
                for (String propName : expectedProps.keySet()) {
                    if (IGNORED_PROPS.contains(propName)) {
                        continue;
                    }
                    JsonNode expectedVal = expectedProps.get(propName);
                    if (SKIP_IF_EXPECTED_NULL.contains(propName) && (expectedVal == null || expectedVal instanceof NullNode)) {
                        continue;
                    }
                    totalProps++;
                    JsonNode actualVal = actualProps.get(propName);
                    if (isSame(propName, expectedVal, actualVal)) {
                        matchedProps++;
                    } else {
                        diffByProperty.merge(propName, 1, Integer::sum);
                        keyDiffs.add(String.format("  属性=%s 期望=%s 实际=%s",
                                propName, expectedVal, actualVal));
                    }
                }
                if (!keyDiffs.isEmpty()) {
                    String rawSigmet = getRawSigmet(expectedProps, actualProps);
                    diffReport.append("[").append(directory.getFileName()).append("] key=").append(key).append("\n");
                    diffReport.append("原始报文:\n").append(rawSigmet).append("\n");
                    for (String d : keyDiffs) {
                        diffReport.append(d).append("\n");
                    }
                    diffReport.append("\n");
                }
            }
        }

        // 写入差异报告
        Path reportPath = dataDir.resolve("../diff-report.txt");
        try (BufferedWriter bw = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8)) {
            bw.write(diffReport.toString());
        }

        // 输出统计
        double accuracy = totalProps == 0 ? 100.0 : 100.0 * matchedProps / totalProps;
        System.out.println("  比较的 Key 总数: " + totalKeys);
        System.out.println("  比较的属性总数: " + totalProps);
        System.out.println("  匹配的属性数: " + matchedProps);
        System.out.println("  差异的属性数: " + (totalProps - matchedProps));
        System.out.printf("  属性准确率: %.2f%%\n", accuracy);
        System.out.println("\n  各属性差异次数:");
        for (Map.Entry<String, Integer> e : diffByProperty.entrySet()) {
            System.out.println("    " + e.getKey() + ": " + e.getValue());
        }
        System.out.println("\n  差异报告已写入: " + reportPath.toAbsolutePath());

        // ===== Phase 3: 几何图形相似度比较 =====
        System.out.println("\n===== Phase 3: 几何图形相似度比较 =====");
        int geomTotalDirs = 0;
        int geomTotalFeatures = 0;
        int geomMatchedFeatures = 0;
        StringBuilder geomReport = new StringBuilder();
        geomReport.append("几何相似度比较报告\n");
        geomReport.append("=================\n");
        geomReport.append("匹配规则: 重叠面积 / min(两个图形面积) >= 90%\n\n");

        for (Path directory : directories) {
            Path geomExpectedPath = directory.resolve("sigmet-geometry-except.txt");
            Path geomActualPath = directory.resolve("sigmet-geojson-actual.txt");
            if (!Files.exists(geomExpectedPath) || !Files.exists(geomActualPath)) continue;

            JsonNode geomExpectedJson = OBJECT_MAPPER.readTree(geomExpectedPath.toFile());
            JsonNode geomActualJson = OBJECT_MAPPER.readTree(geomActualPath.toFile());
            GeometryComparator gc = new GeometryComparator(geomExpectedJson, geomActualJson);
            GeometryComparator.Result result = gc.compare();
            if (result.total == 0) continue;

            geomTotalDirs++;
            geomTotalFeatures += result.total;
            geomMatchedFeatures += result.matched;

            // 仅输出匹配率低于 95% 的目录
            if (result.rate() < 95.0) {
                geomReport.append(String.format("[%s] 匹配率: %.1f%% (%d/%d)",
                        directory.getFileName(), result.rate(), result.matched, result.total));
                geomReport.append("\n");
                for (GeometryComparator.Mismatch m : result.mismatches) {
                    geomReport.append(String.format(
                            "  key=%s  重叠率=%.1f%%  预期=%s(面积=%.2f)  实际=%s(面积=%.2f)\n",
                            m.key, m.overlapRatio * 100,
                            m.expectedType, m.expectedArea,
                            m.actualType, m.actualArea));
                    geomReport.append("  原始报文: ").append(m.rawSigmet != null ? m.rawSigmet : "(无)").append("\n");
                }
            }
        }

        double geomOverall = geomTotalFeatures == 0 ? 100.0 : 100.0 * geomMatchedFeatures / geomTotalFeatures;
        geomReport.append(String.format("\n目录数: %d, 总Feature数: %d, 匹配: %d, 整体匹配率: %.1f%%\n",
                geomTotalDirs, geomTotalFeatures, geomMatchedFeatures, geomOverall));

        Path geomReportPath = dataDir.resolve("../geometry-compare-report.txt");
        try (BufferedWriter bw = Files.newBufferedWriter(geomReportPath, StandardCharsets.UTF_8)) {
            bw.write(geomReport.toString());
        }
        System.out.println("  目录数: " + geomTotalDirs);
        System.out.println("  总 Feature 数: " + geomTotalFeatures);
        System.out.println("  匹配: " + geomMatchedFeatures);
        System.out.printf("  整体匹配率: %.1f%%\n", geomOverall);
        System.out.println("  报告已写入: " + geomReportPath.toAbsolutePath());
    }

    private static Map<String, Map<String, JsonNode>> buildKeyMap(JsonNode featureCollection) {
        Map<String, Map<String, JsonNode>> map = new LinkedHashMap<>();
        JsonNode features = featureCollection.path("features");
        for (JsonNode feature : features) {
            JsonNode props = feature.path("properties");
            String key = buildKey(props);
            if (key != null) {
                Map<String, JsonNode> propMap = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> f = fields.next();
                    propMap.put(f.getKey(), f.getValue());
                }
                map.put(key, propMap);
            }
        }
        return map;
    }

    private static String buildKey(JsonNode props) {
        String icaoId = textOrNull(props, "icaoId");
        String firId = textOrNull(props, "firId");
        String seriesId = textOrNull(props, "seriesId");
        String validFrom = textOrNull(props, "validTimeFrom");
        String validTo = textOrNull(props, "validTimeTo");
        if (icaoId == null && firId == null && seriesId == null && validFrom == null && validTo == null) {
            return null;
        }
        return String.join("|",
                icaoId != null ? icaoId : "",
                firId != null ? firId : "",
                seriesId != null ? seriesId : "",
                validFrom != null ? validFrom : "",
                validTo != null ? validTo : "");
    }

    /** chng 属性中 NC 等同于 null（均表示无变化趋势），其他属性 null 与 absent 等同 */
    private static boolean isSame(String propName, JsonNode a, JsonNode b) {
        if (a == b) return true;
        boolean aIsNull = a == null || a instanceof NullNode;
        boolean bIsNull = b == null || b instanceof NullNode;
        if (aIsNull && bIsNull) return true;
        if ("chng".equals(propName)) {
            String aText = aIsNull ? null : a.asText();
            String bText = bIsNull ? null : b.asText();
            if ("NC".equals(aText) || "NC".equals(bText)) {
                boolean aIsNcOrNull = aText == null || "NC".equals(aText);
                boolean bIsNcOrNull = bText == null || "NC".equals(bText);
                if (aIsNcOrNull && bIsNcOrNull) return true;
            }
        }
        if ("dir".equals(propName)) {
            String aText = aIsNull ? null : a.asText();
            String bText = bIsNull ? null : b.asText();
            if ("S".equals(aText) && ("STRNY".equals(bText) || "STNRY".equals(bText))) return true;
            if ("-".equals(aText) && ("STRNY".equals(bText) || "STNRY".equals(bText))) return true;
        }
        if ("top".equals(propName)) {
            Integer aInt = asInt(a), bInt = asInt(b);
            if (aInt != null && bInt != null && (aInt * 100 == bInt || bInt * 100 == aInt)) return true;
        }
        if ("spd".equals(propName)) {
            String aText = aIsNull ? null : a.asText().replaceFirst("^0+(?!$)", "");
            String bText = bIsNull ? null : b.asText().replaceFirst("^0+(?!$)", "");
            if (aText != null && bText != null && aText.equals(bText)) return true;
            if (aText.equals("0") && bText.equals("UNK")) return true;
        }
        if (aIsNull != bIsNull) return false;
        return a.equals(b);
    }

    private static String textOrNull(JsonNode props, String name) {
        JsonNode node = props.get(name);
        return node != null && !node.isNull() ? node.asText() : null;
    }

    private static Integer asInt(JsonNode node) {
        if (node == null || node instanceof NullNode) return null;
        try { return Integer.parseInt(node.asText()); } catch (NumberFormatException e) { return null; }
    }

    private static String getRawSigmet(Map<String, JsonNode> expectedProps, Map<String, JsonNode> actualProps) {
        JsonNode raw = expectedProps.get("rawSigmet");
        if (raw != null && !(raw instanceof NullNode)) {
            return raw.asText();
        }
        raw = actualProps.get("rawSigmet");
        if (raw != null && !(raw instanceof NullNode)) {
            return raw.asText();
        }
        return "(无原始报文)";
    }
}
