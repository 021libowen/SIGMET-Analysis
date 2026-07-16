package com.weather.sigmet;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从 NavData SQLite 数据库导出 VOR 和航路点坐标，生成 navaids.json。
 * 通过外部 64位 sqlite3 + mod_spatialite 查询。
 */
public final class NavaidGenerator {

    private static final String SQLITE3_PATH = "D:/spatialite/backup/sqlite3.exe";
    private static final String SPATIALITE_PATH = "D:/spatialite/backup/mod_spatialite.dll";

    public static void main(String[] args) throws Exception {
        Path dbPath = args.length > 0 ? Paths.get(args[0]) : Paths.get("src/main/resources/NavData_202607.db");
        Path outputPath = args.length > 1 ? Paths.get(args[1]) : Paths.get("src/main/resources/navaids.json");
        generate(dbPath, outputPath);
    }

    /** 从数据库生成 navaids.json */
    public static void generate(Path dbPath, Path outputPath) throws Exception {
        Map<String, double[]> navaids = new LinkedHashMap<>();

        // 1) 只加载美国 VOR（VOR 查询仅用于 US CONVECTIVE SIGMET，境外站无需加载且会遮盖缺失的美国站）
        String usVorSql = "SELECT load_extension('" + SPATIALITE_PATH + "'); "
                + "SELECT CODE_ID || '|' || GEO_LAT || '|' || GEO_LONG FROM VOR "
                + "WHERE CODE_ID IS NOT NULL AND CODE_FIR LIKE 'K%';";
        loadNavaids(dbPath, usVorSql, navaids);

        // 2) 美国 DESIGNATED_POINT（覆盖同名站）
        String dptSql = "SELECT load_extension('" + SPATIALITE_PATH + "'); "
                + "SELECT CODE_ID || '|' || GEO_LAT || '|' || GEO_LONG FROM DESIGNATED_POINT "
                + "WHERE CODE_FIR LIKE 'K%' AND CODE_ID IS NOT NULL;";
        loadNavaids(dbPath, dptSql, navaids);

        // 写 JSON（紧凑格式，一行一 key 便于流式阅读）
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, double[]> e : navaids.entrySet()) {
            if (first) { first = false; } else { sb.append(","); }
            sb.append("\"").append(e.getKey()).append("\":[")
              .append(e.getValue()[0]).append(",").append(e.getValue()[1]).append("]");
        }
        sb.append("}");

        Files.createDirectories(outputPath.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
        System.out.println("生成完成: " + navaids.size() + " 个台站, 输出 " + outputPath.toAbsolutePath());
    }

    private static void loadNavaids(Path dbPath, String sql, Map<String, double[]> navaids) throws Exception {
        loadNavaids(dbPath, sql, navaids, true);
    }

    private static void loadNavaids(Path dbPath, String sql, Map<String, double[]> navaids, boolean overwrite) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(SQLITE3_PATH, dbPath.toAbsolutePath().toString(), sql);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|", 3);
                if (parts.length < 3) continue;
                String id = parts[0].trim().replaceAll("[\\x00-\\x1f\\x7f]", "");
                if (id.isEmpty()) continue;
                if (!overwrite && navaids.containsKey(id)) continue;
                try {
                    double lat = CoordinateParser.parseDmsDb(parts[1].trim());
                    double lon = CoordinateParser.parseDmsDb(parts[2].trim());
                    navaids.put(id, new double[]{lat, lon});
                } catch (Exception ignored) {}
            }
        }
        process.waitFor();
    }
}
