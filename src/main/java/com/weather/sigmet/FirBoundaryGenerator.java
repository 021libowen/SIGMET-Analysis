package com.weather.sigmet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 NavData SQLite 数据库读取 AIRSPACE 表 CODE_TYPE='FIR' 的数据，
 * 生成 fir-boundaries.geojson。通过外部 64 位 sqlite3 + mod_spatialite 解析几何。
 */
public final class FirBoundaryGenerator {

    private static final String SQLITE3_PATH = "D:/spatialite/backup/sqlite3.exe";
    private static final String SPATIALITE_PATH = "D:/spatialite/backup/mod_spatialite.dll";

    public static void main(String[] args) throws Exception {
        Path dbPath = args.length > 0 ? Paths.get(args[0]) : Paths.get("src/main/resources/NavData_202607.db");
        Path outputPath = args.length > 1 ? Paths.get(args[1]) : Paths.get("src/main/resources/fir-boundaries.geojson");
        generate(dbPath, outputPath);
    }

    /** 从数据库生成 fir-boundaries.geojson */
    public static void generate(Path dbPath, Path outputPath) throws Exception {
        List<Map<String, Object>> features = new ArrayList<>();
        WKTReader wktReader = new WKTReader();
        GeoJsonWriter geoJsonWriter = new GeoJsonWriter();

        String sql = "SELECT load_extension('" + SPATIALITE_PATH + "'); "
                + "SELECT CODE_ID || '|' || TXT_NAME || '|' || ST_AsText(Geometry) "
                + "FROM AIRSPACE WHERE CODE_TYPE = 'FIR';";

        ProcessBuilder pb = new ProcessBuilder(SQLITE3_PATH, dbPath.toAbsolutePath().toString(), sql);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|", 3);
                if (parts.length < 3) continue;
                String codeId = parts[0].trim();
                String name = parts[1].trim();
                String wkt = parts[2].trim();
                if (codeId.isEmpty() || wkt.isEmpty()) continue;
                try {
                    Geometry geom = wktReader.read(wkt);
                    Map<String, Object> feature = new LinkedHashMap<>();
                    feature.put("type", "Feature");

                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("id", codeId);
                    props.put("name", name);
                    feature.put("properties", props);
                    feature.put("geometry", geoJsonWriter.geometryToMap(geom));
                    features.add(feature);
                } catch (Exception e) {
                    System.out.println("跳过 " + codeId + ": " + e.getMessage());
                }
            }
        }
        process.waitFor();

        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("type", "FeatureCollection");
        collection.put("features", features);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(collection);

        Files.createDirectories(outputPath.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write(json);
        }

        System.out.println("生成完成: " + features.size() + " 个FIR, 输出 " + outputPath.toAbsolutePath());
    }
}
