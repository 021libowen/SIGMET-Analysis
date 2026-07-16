package com.siniswift.efb.sigmet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SigmetParserTest {
    @Test
    void parsesDirectPolygonFeature() throws Exception {
        String text = "Hazard: TS\n"
                + "WSHO31 MHTG 040010\n"
                + "MHTG SIGMET C1 VALID 040010/040410 MHTG-\n"
                + "MHCC CENTRAL AMERICAN FIR EMBD TS OBS AT 0000Z\n"
                + "WI N1001 W10428 - N1153 W09841 - N0951 W09553 - N1001 W10428 TOP FL520 MOV W 05KT WKN=\n";
        List<SigmetFeature> features = new SigmetParser().parse(text, "20260604100640");
        assertEquals(1, features.size());
        SigmetFeature feature = features.get(0);
        assertEquals("MHTG", feature.icaoId);
        assertEquals("MHTG", feature.firId);
        assertEquals("MHCC CENTRAL AMERICAN", feature.firName);
        assertEquals("C1", feature.seriesId);
        assertEquals(HazardType.TS, feature.hazard);
        assertEquals("EMBD", feature.qualifier);
        assertEquals(52000, feature.top);
        assertEquals("W", feature.dir);
        assertEquals("05", feature.spd);
        assertEquals("WKN", feature.chng);
        assertFalse(feature.geometry.isEmpty());
    }
    @Test
    void parsesSampleFileAndExportsGeoJson() throws Exception {
        Path input = Paths.get("data", "20260605072049", "sigmet.txt");
        String text = new String(Files.readAllBytes(input), StandardCharsets.UTF_8);
        List<SigmetFeature> features = new SigmetParser().parse(text, "20260605072049");
        String geoJson = new GeoJsonWriter().write(features);

        Path output = Paths.get("target", "test-output", "20260605072049.geojson");
        Files.createDirectories(output.getParent());
        Files.write(output, geoJson.getBytes(StandardCharsets.UTF_8));

        JsonNode root = new ObjectMapper().readTree(geoJson);
        assertEquals("FeatureCollection", root.path("type").asText());
        assertFalse(features.isEmpty());
        assertEquals(features.size(), root.path("features").size());
    }
    @Test
    void clipsWOfLineToWmfcBoundary() throws Exception {
        String text = "Hazard: TS\n"
                + "WSMS31 WMKK 040151\n"
                + "WMFC SIGMET 3 VALID 040200/040400 WMKK-\n"
                + "WMFC KUALA LUMPUR FIR EMBD TS\n"
                + "OBS W OF LINE N0849 E09709 - N0600 E09555 TOP FL480 MOV SW NC=\n";
        List<SigmetFeature> features = new SigmetParser().parse(text, "20260604100640");
        String geoJson = new GeoJsonWriter().write(features);
        JsonNode coordinates = new ObjectMapper().readTree(geoJson).path("features").get(0).path("geometry").path("coordinates").get(0);

        assertEquals(1, features.size());
        assertFalse(geoJson.contains("-90"));
        assertFalse(geoJson.contains("-180"));
        assertFalse(coordinates.isEmpty());
    }
    @Test
    void parsesBrazilSingleLineFirName() throws Exception {
        String text = "Hazard: TS\n"
                + "WSBZ23 SBGL 032325\n"
                + "SBAO SIGMET 52 VALID 032330/040330 SBAO - SBAO ATLANTICO FIR EMBD TS FCST WI S2125 W02344 - S2408 W02630 - S2659 W02222 - S2420 W01941 - S2125 W02344 FL450 STNR NC=\n";
        SigmetFeature feature = new SigmetParser().parse(text, "20260604100640").get(0);
        assertEquals("SBAO ATLANTICO", feature.firName);
        assertEquals("EMBD", feature.qualifier);
        assertEquals(45000, feature.top);
    }

    @Test
    void parsesKkciMultiFirUsingLastNamedFir() throws Exception {
        String text = "Hazard: TS\n"
                + "WSNT05 KKCI 032310\n"
                + "SIGA0E\n"
                + "KZMA KZHU SIGMET ECHO 5 VALID 032310/040310 KKCI-\n"
                + "MIAMI OCEANIC FIR HOUSTON OCEANIC FIR EMBD TS OBS AT 2310Z WI\n"
                + "N2815 W08816 - N2728 W08352 - N2401 W08256 - N2815 W08816. TOP FL510. MOV E 25KT.\n"
                + "INTSF.\n";
        SigmetFeature feature = new SigmetParser().parse(text, "20260604100640").get(0);
        assertEquals("KZHU", feature.firId);
        assertEquals("HOUSTON OCEANIC", feature.firName);
        assertEquals("EMBD", feature.qualifier);
    }

    @Test
    void parsesWideLineAsRectangle() throws Exception {
        String text = "Hazard: TS\n"
                + "WSCN03 CWAO 032340\n"
                + "CZWG SIGMET E6 VALID 032340/040340 CWEG-\n"
                + "CZWG WINNIPEG FIR FRQ TS OBS WI 50NM WID LINE BTN N5104 W09635 - N5242 W09634\n"
                + "TOP FL360 MOV NE 20KT NC=\n";
        SigmetFeature feature = new SigmetParser().parse(text, "20260604100640").get(0);
        assertEquals("FRQ", feature.qualifier);
        assertEquals(5, feature.geometry.getCoordinates().length);
    }

    @Test
    void myTest() throws Exception{
        String text = "WSTU31 LTAA 100845\n" +
                "LTAA SIGMET 2 VALID 100841/101241 LTAC-\n" +
                "LTAA ANKARA FIR EMBD TS FCST SE OF LINE N3600 E03148 - N3805 E03436 -\n" +
                "N3655 E03813 MOV N 12KT NC=";
        String geoJson = new GeoJsonWriter().write(new SigmetParser().parse(text, "20260604100640"));
        System.out.println(geoJson);
    }

    @Test
    void generatesTimeSlotsFromGeoJson() throws Exception {
        Path geojsonFile = Paths.get("data", "20260610172419", "sigmet-geojson-actual.txt");
        List<SigmetFeature> features = TimeSlotExporter.parseFeatures(geojsonFile);
        assertFalse(features.isEmpty());

        // 手动指定一个落在 Feature 有效期内的 base time
        LocalDateTime baseTime = LocalDateTime.of(2026, 6, 10, 7, 24);

        Path output = Paths.get("target", "test-output", "timeslots.json");
        new TimeSlotExporter().export(baseTime, features, output);
        assertTrue(Files.exists(output));

        // 验证输出结构
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(output.toFile());
        assertEquals(6, root.size(), "应该有6个时隙");
        JsonNode firstSlot = root.get(0);
        assertFalse(firstSlot.path("time").asText().isEmpty());
        assertTrue(firstSlot.path("data").isArray());
        System.out.println("Output: " + output.toAbsolutePath());
    }

}
