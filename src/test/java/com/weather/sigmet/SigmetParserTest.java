package com.weather.sigmet;

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
        assertFalse(feature.geojson == null || feature.geojson.isEmpty());
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
    void parsesCnlCancellationSigmet() throws Exception {
        String text = "WSPA04 PHFO 130146\n" +
                "SIGPAQ\n" +
                "KZAK SIGMET QUEBEC 3 VALID 130145/130440 PHFO-\n" +
                "OAKLAND OCEANIC FIR CNL SIGMET QUEBEC 2 VALID 130040/130440.\n" +
                "SIGMET QUEBEC HAS BEEN REPLACED BY TC SIGMET ROMEO.";
        SigmetFeature feature = new SigmetParser().parse(text, "20260713020640").get(0);
        assertEquals(HazardType.CNL, feature.hazard);
        assertEquals("Cancellation", feature.category);
        assertEquals("2", feature.qualifier);
        assertEquals("OAKLAND OCEANIC", feature.firName);
        // CNL: 从报文体提取被取消报文的 seriesId（QUEBEC 2 → 2）
        assertEquals("2", feature.seriesId);
        assertTrue(feature.geojson == null || feature.geojson.isEmpty());
    }

    @Test
    void parsesConvectiveSigmet34C() throws Exception {
        String text = "\n" +
                "WSUS31 KKCI 140555\n" +
                "SIGE\n" +
                "MKCE WST 140555\n" +
                "CONVECTIVE SIGMET 25E\n" +
                "VALID UNTIL 0755Z\n" +
                "FL AND CSTL WTRS\n" +
                "FROM 20SE CEW-50W TLH-90SW TLH-60S CEW-20SE CEW\n" +
                "AREA TS MOV FROM 27020KT. TOPS TO FL450.\n" +
                "\n" +
                "OUTLOOK VALID 140755-141155\n" +
                "FROM BWG-180ESE ECG-150SSE ILM-140SE MIA-EYW-100W SRQ-170SE\n" +
                "LEV-BWG\n" +
                "WST ISSUANCES EXPD. REFER TO MOST RECENT ACUS01 KWNS FROM STORM\n" +
                "PREDICTION CENTER FOR SYNOPSIS AND METEOROLOGICAL DETAILS.";
        SigmetFeature feature = new SigmetParser().parse(text, "20260713020640").get(0);
        assertEquals("KKCI", feature.icaoId);
        assertEquals("25E", feature.seriesId);
        assertEquals(HazardType.TS, feature.hazard);
        assertEquals("Convective", feature.category);
        assertEquals(45000, feature.top);
        assertEquals("270", feature.dir);
        assertEquals("20", feature.spd);
        assertEquals("FL AND CSTL WTRS", feature.firName);
        assertFalse(feature.geojson == null || feature.geojson.isEmpty());
    }

    @Test
    void myTest() throws Exception{
        String text = "WSUS32 KKCI 150455 SIGC MKCC WST 150455 CONVECTIVE SIGMET 16C VALID UNTIL 0655Z OK FROM 40W END-30SSW END-60W OKC-30SE MMB-40W END DMSHG AREA TS MOV LTL. TOPS TO FL430.";
        List<SigmetFeature> features = new SigmetParser().parse(text, "20260715070640");
        System.out.println("count: " + features.size());
        if (!features.isEmpty()) {
            System.out.println("geojson: " + features.get(0).geojson);
        }
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

    @Test
    void parsesSingleSigmetToFlatJson() throws Exception {
        String text = "Hazard: TURB\n" +
                "WSJP31 RJTD 140137\n" +
                "RJJJ SIGMET Q01 VALID 140137/140337 RJTD-\n" +
                "RJJJ FUKUOKA FIR SEV TURB OBS AT 0120Z N3808E14055 FL350 MOV E 30KT\n" +
                "NC=";
        List<SigmetFeature> features = new SigmetParser().parse(text, "20260614070000");
        assertEquals(1, features.size());

        String json = new GeoJsonWriter().writeFlat(features.get(0));
        JsonNode root = new ObjectMapper().readTree(json);
        assertEquals("RJTD", root.path("icaoId").asText());
        assertEquals("RJJJ", root.path("firId").asText());
        assertEquals("TURB", root.path("hazard").asText());
        assertEquals("Non-Convective", root.path("category").asText());
        assertFalse(root.path("issued").asText().isEmpty());
        assertEquals("SEV", root.path("qualifier").asText());
        assertEquals(35000, root.path("top").asInt());
        assertFalse(root.path("geojson").asText().isEmpty());
        // verify geojson is valid JSON
        JsonNode geojson = new ObjectMapper().readTree(root.path("geojson").asText());
        assertEquals("Polygon", geojson.path("type").asText());
    }

    @Test
    void parsesUsConvectiveSigmet() throws Exception {
        String text = "Hazard: TS\n"
                + "WSUS33 KKCI 091855\n"
                + "SIGW\n"
                + "MKCW WST 091855\n"
                + "CONVECTIVE SIGMET 65W\n"
                + "VALID UNTIL 2055Z\n"
                + "WY\n"
                + "FROM 30ESE SHR-20N DDY-30ENE OCS-30ESE JAC-70NE JAC-30ESE SHR\n"
                + "DVLPG AREA TS MOV FROM 29010KT. TOPS TO FL410.\n";
        List<SigmetFeature> features = new SigmetParser().parse(text, "20260709185500");
        assertEquals(1, features.size());
        SigmetFeature f = features.get(0);
        assertEquals("KKCI", f.icaoId);
        assertEquals("65W", f.seriesId);
        assertEquals(HazardType.TS, f.hazard);
        assertEquals("Convective", f.category);
        assertEquals(41000, f.top);
        assertEquals("290", f.dir);
        assertEquals("10", f.spd);
        assertEquals("DVLPG", f.qualifier);
        assertEquals("WY", f.firName);
        assertFalse(f.geojson == null || f.geojson.isEmpty());
        JsonNode geojson = new ObjectMapper().readTree(f.geojson);
        assertEquals("Polygon", geojson.path("type").asText());
    }

    @Test
    void parsesUsConvectiveSigmetWithoutOutlook() throws Exception {
        String text = "Hazard: TS\n"
                + "WSUS32 KKCI 100255\n"
                + "SIGE\n"
                + "CONVECTIVE SIGMET 12E\n"
                + "VALID UNTIL 0455Z\n"
                + "ND SD\n"
                + "FROM 40WSW BIS-30WNW BIS-20NE BIS-40WSW BIS\n"
                + "DVLPG AREA TS MOV FROM 27015KT. TOPS TO FL380.\n"
                + "OUTLOOK VALID 100455-100855\n"
                + "FROM 30W BIS-30ENE DPR-30SSE FAR-30W BIS\n"
                + "WST ISSUANCES POSSIBLE.\n";
        List<SigmetFeature> features = new SigmetParser().parse(text, "20260710025500");
        assertEquals(1, features.size());
        SigmetFeature f = features.get(0);
        assertEquals("12E", f.seriesId);
        assertEquals(38000, f.top);
        assertEquals("270", f.dir);
        assertEquals("15", f.spd);
        // OUTLOOK 之后的内容不应影响解析
        assertFalse(f.geojson == null || f.geojson.isEmpty());
    }

    @Test
    void parsesUsConvectiveSigmetRealWorld() throws Exception {
        String text = "WVMX31 MMMX 140604 CCC\n" +
                "MMEX SIGMET 1 VALID 140510/141110 MMMX-\n" +
                "MMFR MEXICO FIR VA POPOCATEPETL PSN N1901 W09837 OBS\n" +
                "AT 140510Z\n" +
                "VA CLD BTN SFC/FL200 WI N1908W09902 N1902W09837 N1900W09837 N1851W098\n" +
                "56\n" +
                "N1908W09902\n" +
                "MOV W 15KT NC.\n" +
                "OUTLK 14/1110Z VA CLD BTN SFC/FL200 WI N1908W09902 N1902W09837 N1900W\n" +
                "09837 N1851W09856\n" +
                "N1908W09902\n" +
                "AFFECTED AIRWAYS ARRIVALS LARLO 4A AND ESPOS 3A AND DEPARTURES TEVOS\n" +
                "2A.\n" +
                "=";
        List<SigmetFeature> features = new SigmetParser().parse(text, "20260713005500");
        assertEquals(1, features.size());
        SigmetFeature f = features.get(0);
        assertEquals("MMMX", f.icaoId);
        assertEquals("MMEX", f.firId);
        assertEquals("MMFR MEXICO", f.firName);
        assertEquals("1", f.seriesId);
        assertEquals(HazardType.VA, f.hazard);
        assertEquals("Volcanic Ash", f.category);
        assertEquals(20000, f.top);
        assertEquals("W", f.dir);
        assertEquals("15", f.spd);
        assertTrue(f.validTimeFrom != null);
        assertTrue(f.validTimeTo != null);
        assertFalse(f.geojson == null || f.geojson.isEmpty());
        JsonNode geojson = new ObjectMapper().readTree(f.geojson);
        assertTrue(geojson.path("type").asText().equals("Polygon") || geojson.path("type").asText().equals("MultiPolygon"));
        // OUTLK 之后的内容不应被解析进 geometry
        JsonNode coords = "MultiPolygon".equals(geojson.path("type").asText())
                ? geojson.path("coordinates").get(0).get(0)
                : geojson.path("coordinates").get(0);
        assertTrue(coords.size() >= 4, "多边形至少4个坐标点(闭合)");
    }


}
