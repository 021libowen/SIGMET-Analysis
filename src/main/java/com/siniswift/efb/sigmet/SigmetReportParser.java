package com.siniswift.efb.sigmet;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SigmetReportParser {
    private static final Pattern WMO = Pattern.compile("^\\S+\\s+(\\S+)\\s+\\d{6}");
    private static final Pattern SIGMET = Pattern.compile("(?m)^(.+?)\\s+SIGMET\\s+(.+?)\\s+VALID\\s+(\\d{6})/(\\d{6})\\s+(\\S+)-?");
    private static final Pattern FIR_IN_BODY = Pattern.compile("(?s)(?:^|\\s)(?:([A-Z0-9]{4})\\s+)?([A-Z][A-Z0-9 ]*?)\\s+FIR(?:/UIR|\\b)\\s*(.*)");
    private static final Pattern QUALIFIER = Pattern.compile("\\b(EMBD|FRQ|OCNL|SQL|SEV|MOD|ISOL|OBSC)\\b");
    private static final Pattern VA_ERUPTION_MT = Pattern.compile("\\bVA\\s+ERUPTION\\s+MT\\s+([A-Z][A-Z0-9 ]*?)(?:\\s*\\([^)]*\\))?\\s+PSN\\b");
    private static final Pattern VA_SIMPLE_NAME = Pattern.compile("\\bVA\\s+([A-Z][A-Z0-9]*?)\\s+PSN\\b");
    private static final Pattern TC_NAME = Pattern.compile("\\bTC\\s+([A-Z][A-Z0-9 ]*?)\\s+OBS\\b");
    private static final Pattern TOP = Pattern.compile("(?:TOP\\s*(?:ABV\\s+)?|/)FL\\s*(\\d{2,4})|TOP\\s+(\\d{3,5})FT", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEVEL_RANGE = Pattern.compile("\\b(SFC|FL\\d{2,4}|\\d{3,5}FT)/(FL\\d{2,4}|\\d{3,5}FT)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FL_RANGE = Pattern.compile("\\bFL(\\d{2,4})/(\\d{2,4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABV_FL = Pattern.compile("\\bABV\\s+FL(\\d{2,4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SINGLE_FL_TOP = Pattern.compile("\\bFL(\\d{3,4})\\s+(?:STNR|MOV|NC|WKN|INTSF|FCST)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_FL = Pattern.compile("\\bFL(\\d{2,4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOV = Pattern.compile("\\bMOV\\s+([A-Z]{1,5}(?:-[A-Z]{1,5})?)(?:\\s*(AT\\s+)?(\\d{1,3})\\s*(KT|KMH)?)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern STNR = Pattern.compile("\\bSTNR\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHNG = Pattern.compile("\\b(NC|WKN|INTSF)\\b[\\s.]*=?(?=\\s|$|=)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // 方向/速度常量
    private static final String DIR_UNKNOWN = "-";
    private static final String SPD_UNKNOWN = "UNK";
    private static final String SPD_ZERO = "0";
    private static final String UNIT_KMH = "KMH";
    private static final String STNR_STR = "STNR";
    // 高度常量
    private static final String LVL_SFC = "SFC";
    private static final String LVL_FL = "FL";
    private static final String LVL_FT = "FT";
    // qualifier 常量
    private static final String MT_PREFIX = "MT ";
    private static final String VA_MT_VOLCAN = "VA MT VOLCAN";
    private static final String EMPTY = "";

    private final GeometryParser geometryParser;

    SigmetReportParser(GeometryParser geometryParser) {
        this.geometryParser = geometryParser;
    }

    Optional<SigmetFeature> parse(SigmetBlock block, LocalDateTime sampleTime) {
        Matcher sigmet = SIGMET.matcher(block.raw);
        if (!sigmet.find()) {
            return Optional.empty();
        }
        SigmetFeature feature = new SigmetFeature();
        feature.rawSigmet = block.raw;
        feature.icaoId = parseIcao(block.raw);
        String body = reportBody(block.raw, sigmet.end());
        feature.hazard = block.hazard != null ? block.hazard : HazardType.detectFrom(body);
        feature.firId = parseFirId(sigmet.group(1), body);
        feature.seriesId = sigmet.group(2).trim().replaceAll("\\s+", " ");
        feature.validTimeFrom = parseValid(sigmet.group(3), sampleTime.toLocalDate());
        feature.validTimeTo = parseValid(sigmet.group(4), sampleTime.toLocalDate());
        if (feature.validTimeTo.isBefore(feature.validTimeFrom)) {
            feature.validTimeTo = feature.validTimeTo.plusSeconds(86400);
        }
        parseFirName(feature, body);
        parseQualifier(feature, body);
        parseLevels(feature, block.raw);
        parseMovement(feature, block.raw);
        parseChange(feature, block.raw);
        Optional.ofNullable(geometryParser.parse(block.raw, feature.firId, feature.hazard, feature.dir).orElse(null)).ifPresent(g -> feature.geometry = g);
        if (feature.geometry == null || feature.geometry.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(feature);
    }

    private String parseIcao(String raw) {
        Matcher matcher = WMO.matcher(raw.replaceFirst("^\\s+", ""));
        return matcher.find() ? matcher.group(1) : null;
    }

    private String parseFirId(String sigmetPrefix, String bodyText) {
        String[] ids = sigmetPrefix.trim().split("\\s+");
        if (ids.length == 1) {
            return ids[0];
        }
        String body = bodyText.replace("\n", " ").replaceAll("\\s+", " ");
        String lastKnownId = null;
        for (String id : ids) {
            String name = usFirName(id);
            if (name != null && body.contains(name + " FIR")) {
                lastKnownId = id;
            }
        }
        return lastKnownId != null ? lastKnownId : ids[ids.length - 1];
    }

    private String reportBody(String raw, int sigmetHeaderEnd) {
        return raw.substring(sigmetHeaderEnd).replaceFirst("^-\\s*", "").replace("\n", " ").replaceAll("\\s+", " ").trim();
    }

    private String usFirName(String id) {
        if ("KZAK".equals(id)) return "OAKLAND OCEANIC";
        if ("KZHU".equals(id)) return "HOUSTON OCEANIC";
        if ("KZMA".equals(id)) return "MIAMI OCEANIC";
        if ("KZWY".equals(id)) return "NEW YORK OCEANIC";
        return null;
    }

    private void parseFirName(SigmetFeature feature, String body) {
        String knownUsName = usFirName(feature.firId);
        if (knownUsName != null && body.contains(knownUsName + " FIR")) {
            feature.firName = knownUsName;
            return;
        }
        Matcher matcher = FIR_IN_BODY.matcher(body);
        if (matcher.find()) {
            String id = matcher.group(1);
            String name = matcher.group(2).trim().replaceAll("\\s+", " ");
            feature.firName = id == null ? name : id + " " + name;
        }
    }

    private void parseQualifier(SigmetFeature feature, String body) {
        if (HazardType.VA == feature.hazard) {
            if (body.contains(VA_MT_VOLCAN)) {
                feature.qualifier = EMPTY;
                return;
            }
            Matcher eruption = VA_ERUPTION_MT.matcher(body);
            if (eruption.find()) {
                String name = eruption.group(1).trim().replaceAll("\\s+", " ");
                String firstWord = name.split("\\s+")[0];
                feature.qualifier = name.contains(" ") ? MT_PREFIX + firstWord : name;
                return;
            }
            Matcher simple = VA_SIMPLE_NAME.matcher(body);
            if (simple.find()) {
                feature.qualifier = simple.group(1).trim();
                return;
            }
            feature.qualifier = EMPTY;
            return;
        }
        if (HazardType.TC == feature.hazard) {
            Matcher tc = TC_NAME.matcher(body);
            if (tc.find()) {
                feature.qualifier = tc.group(1).trim().replaceAll("\\s+", " ");
                return;
            }
        }
        if(HazardType.TS == feature.hazard) {
            feature.qualifier = closestQualifierToTs(body);
            return;
        }
        Matcher qm = QUALIFIER.matcher(body);
        if (qm.find()) {
           feature.qualifier = qm.group(1).toUpperCase();
        }
    }

    private String closestQualifierToTs(String body) {
        Matcher ts = Pattern.compile("\\bTS\\w*\\b", Pattern.CASE_INSENSITIVE).matcher(body);
        if (!ts.find()) {
            return null;
        }
        int tsPos = ts.start();
        Matcher qm = QUALIFIER.matcher(body);
        String closest = null;
        int minDist = Integer.MAX_VALUE;
        while (qm.find()) {
            int dist = Math.abs(qm.start() - tsPos);
            if (dist < minDist) {
                minDist = dist;
                closest = qm.group(1).toUpperCase();
            }
        }
        if (closest == null) {
            return body.contains(" TS ") || body.contains(" TS OBS") || body.contains(" TS FCST") ? EMPTY : null;
        }
        return closest;
    }

    private void parseLevels(SigmetFeature feature, String raw) {
        Matcher abv = ABV_FL.matcher(raw);
        if (abv.find()) {
            String fl = abv.group(1);
            if (fl.length() == 4) fl = fl.substring(0, 3);
            feature.base = Integer.parseInt(fl) * 100;
        }
        // VA OBS: 只取 OBS 段的高度（用正则从 OBS 到 FCST 之间提取），忽略 FCST 段的 SFC/FLxxx
        String levelText = raw;
        if (HazardType.VA == feature.hazard) {
            java.util.regex.Matcher obsFl = java.util.regex.Pattern.compile(
                "\\bOBS\\b.*?\\bFL(\\d{2,4})\\b", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(raw);
            if (obsFl.find()) {
                String fl = obsFl.group(1);
                if (fl.length() == 4) fl = fl.substring(0, 3);
                feature.top = Integer.parseInt(fl) * 100;
                feature.base = 0;
                return;
            }
        }
        Matcher range = LEVEL_RANGE.matcher(levelText);
        if (range.find()) {
            feature.base = parseLevel(range.group(1));
            feature.top = parseLevel(range.group(2));
            return;
        }
        Matcher flRange = FL_RANGE.matcher(levelText);
        if (flRange.find()) {
            feature.base = Integer.parseInt(flRange.group(1)) * 100;
            feature.top = Integer.parseInt(flRange.group(2)) * 100;
            return;
        }
        Matcher top = TOP.matcher(levelText);
        Integer lastTop = null;
        while (top.find()) {
            if (top.group(1) != null) {
                String fl = top.group(1);
                if (fl.length() == 4) fl = fl.substring(0, 3);
                lastTop = Integer.parseInt(fl) * 100;
            } else if (top.group(2) != null) {
                lastTop = Integer.parseInt(top.group(2));
            }
        }
        if (lastTop == null) {
            Matcher single = SINGLE_FL_TOP.matcher(levelText);
            if (single.find()) {
                String fl = single.group(1);
                if (fl.length() == 4) fl = fl.substring(0, 3);
                lastTop = Integer.parseInt(fl) * 100;
            }
        }
        if (lastTop == null) {
            Matcher anyFl = ANY_FL.matcher(levelText);
            Integer lastFl = null;
            while (anyFl.find()) {
                String fl = anyFl.group(1);
                if (fl.length() == 4) fl = fl.substring(0, 3);
                lastFl = Integer.parseInt(fl) * 100;
            }
            lastTop = lastFl;
        }
        feature.top = lastTop;
    }

    private int parseLevel(String value) {
        String upper = value.toUpperCase();
        if (LVL_SFC.equals(upper)) {
            return 0;
        }
        if (upper.startsWith(LVL_FL)) {
            return Integer.parseInt(upper.substring(2)) * 100;
        }
        return Integer.parseInt(upper.replace(LVL_FT, EMPTY));
    }

    private void parseMovement(SigmetFeature feature, String raw) {
        Matcher matcher = MOV.matcher(raw);
        if (matcher.find()) {
            String dir = matcher.group(1).toUpperCase();
            feature.dir = dir;
            if (matcher.group(3) == null || matcher.group(2) != null) {
                feature.spd = SPD_UNKNOWN;
            } else if (UNIT_KMH.equalsIgnoreCase(matcher.group(4))) {
                feature.spd = String.valueOf(Math.round(Integer.parseInt(matcher.group(3)) / 1.852));
            } else if (HazardType.VA == feature.hazard) {
                feature.spd = matcher.group(3);
            } else {
                feature.spd = matcher.group(3).length() == 1 ? SPD_ZERO + matcher.group(3) : matcher.group(3);
            }
            if (STNR_STR.equals(feature.dir) && SPD_UNKNOWN.equals(feature.spd)) {
                feature.dir = DIR_UNKNOWN;
                feature.spd = SPD_ZERO;
            }
            return;
        }
        if (STNR.matcher(raw).find()) {
            feature.dir = DIR_UNKNOWN;
            feature.spd = SPD_ZERO;
        }
    }

    private void parseChange(SigmetFeature feature, String raw) {
        if (Pattern.compile("\\bMOV\\s+[A-Z]{1,3}\\s+AT\\s+\\d", Pattern.CASE_INSENSITIVE).matcher(raw).find()) {
            return;
        }
        Matcher matcher = CHNG.matcher(raw);
        if (matcher.find()) {
            feature.chng = matcher.group(1).toUpperCase();
        }
    }

    private Instant parseValid(String ddhhmm, LocalDate baseDate) {
        int day = Integer.parseInt(ddhhmm.substring(0, 2));
        int hour = Integer.parseInt(ddhhmm.substring(2, 4));
        int minute = Integer.parseInt(ddhhmm.substring(4, 6));
        LocalDate date = baseDate.withDayOfMonth(Math.min(day, baseDate.lengthOfMonth()));
        if (day > baseDate.getDayOfMonth() + 15) {
            date = date.minusMonths(1);
        } else if (day < baseDate.getDayOfMonth() - 15) {
            date = date.plusMonths(1);
        }
        if (hour == 24) {
            hour = 0;
            date = date.plusDays(1);
        }
        return date.atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }

}
