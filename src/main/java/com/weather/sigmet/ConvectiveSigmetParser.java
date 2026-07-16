package com.weather.sigmet;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析美国本土 CONVECTIVE SIGMET 报文（VOR 航路点格式）。
 * 仅解析 OUTLOOK 之前的部分。
 */
final class ConvectiveSigmetParser {

    private static final Pattern WMO = Pattern.compile("^\\S+\\s+(\\S+)\\s+(\\d{6})");
    private static final Pattern CONVECTIVE_HEADER = Pattern.compile("CONVECTIVE\\s+SIGMET\\s+(\\d+[EWC]?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALID_UNTIL = Pattern.compile("VALID\\s+UNTIL\\s+(\\d{2})(\\d{2})Z", Pattern.CASE_INSENSITIVE);
    private static final Pattern WAYPOINT = Pattern.compile("(\\d+)?\\s*(N|NNE|NE|ENE|E|ESE|SE|SSE|S|SSW|SW|WSW|W|WNW|NW|NNW)\\s+([A-Z0-9]{3,5})", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_VOR = Pattern.compile("^[A-Z0-9]{3,5}$");
    private static final Pattern MOV_FROM = Pattern.compile("MOV\\s+FROM\\s+(\\d{2,3})(\\d{2,3})KT", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOPS_TO = Pattern.compile("TOPS?\\s+(?:TO|ABV)\\s+FL(\\d{2,4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATE_LINE = Pattern.compile("(?m)^([A-Z]{2}(?:\\s+[A-Z]{2})*)\\s*$");

    private static final Map<String, Double> BEARING = new HashMap<>();
    static {
        BEARING.put("N", 0.0);
        BEARING.put("NNE", 22.5);
        BEARING.put("NE", 45.0);
        BEARING.put("ENE", 67.5);
        BEARING.put("E", 90.0);
        BEARING.put("ESE", 112.5);
        BEARING.put("SE", 135.0);
        BEARING.put("SSE", 157.5);
        BEARING.put("S", 180.0);
        BEARING.put("SSW", 202.5);
        BEARING.put("SW", 225.0);
        BEARING.put("WSW", 247.5);
        BEARING.put("W", 270.0);
        BEARING.put("WNW", 292.5);
        BEARING.put("NW", 315.0);
        BEARING.put("NNW", 337.5);
    }

    private final NavaidRepository navaidRepo;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    ConvectiveSigmetParser(NavaidRepository navaidRepo) {
        this.navaidRepo = navaidRepo;
    }

    Optional<SigmetFeature> parse(String raw, LocalDateTime sampleTime) {
        // 截断 OUTLOOK 之后的内容
        String body = raw;
        int outlookIdx = body.indexOf("OUTLOOK");
        if (outlookIdx >= 0) {
            body = body.substring(0, outlookIdx);
        }

        SigmetFeature feature = new SigmetFeature();
        feature.rawSigmet = raw;

        // WMO 头行 → icaoId, issued
        Matcher wmo = WMO.matcher(body);
        if (!wmo.find()) return Optional.empty();
        feature.icaoId = wmo.group(1);
        feature.issued = toDate(wmo.group(2), sampleTime.toLocalDate());

        // CONVECTIVE SIGMET header → seriesId
        Matcher conv = CONVECTIVE_HEADER.matcher(body);
        if (!conv.find()) return Optional.empty();
        feature.seriesId = conv.group(1);

        // VALID UNTIL → validTimeTo（validTimeFrom = issued time）
        feature.validTimeFrom = feature.issued;
        Matcher vu = VALID_UNTIL.matcher(body);
        if (vu.find()) {
            int hour = Integer.parseInt(vu.group(1));
            int minute = Integer.parseInt(vu.group(2));
            LocalDate date = sampleTime.toLocalDate();
            if (feature.issued != null) {
                java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                cal.setTime(feature.issued);
                if (hour < cal.get(java.util.Calendar.HOUR_OF_DAY)) {
                    date = date.plusDays(1);
                }
            }
            feature.validTimeTo = Date.from(date.atTime(hour, minute).toInstant(ZoneOffset.UTC));
        }

        // Hazard 检测
        feature.hazard = HazardType.detectFrom(body);
        if (feature.hazard != null) feature.category = feature.hazard.getCategory();

        // firId/firName: 尝试提取州代码或描述性区域名
        feature.firId = feature.icaoId;
        String[] lines = body.split("\n");
        StringBuilder stateNames = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            // 2字母州代码行（如 "TX", "NM AZ UT NV"）
            if (trimmed.matches("^[A-Z]{2}(\\s+[A-Z]{2})*$") && !trimmed.equals("WI") && !trimmed.equals("NC")
                    && !trimmed.equals("TS") && !trimmed.equals("KT")) {
                if (stateNames.length() > 0) stateNames.append(" ");
                stateNames.append(trimmed);
            }
            // 描述性区域名（如 "FL AND CSTL WTRS"）——全大写字母+空格
            else if (trimmed.matches("^[A-Z][A-Z\\s]+$") && !trimmed.matches("^\\S+\\s+\\d")
                    && !trimmed.startsWith("FROM") && !trimmed.startsWith("VALID")
                    && !trimmed.startsWith("CONVECTIVE") && !trimmed.startsWith("SIG")
                    && !trimmed.startsWith("MK") && !trimmed.startsWith("WSUS")
                    && !trimmed.startsWith("AREA") && !trimmed.startsWith("DVLPG")
                    && !trimmed.startsWith("MOV") && !trimmed.startsWith("TOPS")
                    && !trimmed.startsWith("OUTLOOK") && !trimmed.startsWith("WST")
                    && !trimmed.startsWith("REF") && !trimmed.equals("WST ISSUANCES EXPD")) {
                if (stateNames.length() > 0) stateNames.append(" ");
                stateNames.append(trimmed);
            }
        }
        feature.firName = stateNames.length() > 0 ? stateNames.toString() : "US-CONUS";

        // Qualifier
        feature.qualifier = parseQualifier(body, feature.hazard);

        // 高度
        parseLevels(feature, body);

        // 移动
        parseMovement(feature, body);

        // Geometry: FROM 航路点链 → Polygon
        Geometry geom = parseFromChain(body);
        if (geom != null) {
            try {
                feature.geojson = new GeoJsonWriter().geometryToJson(geom);
            } catch (Exception ignored) {}
        }

        if (feature.geojson == null || feature.geojson.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(feature);
    }

    private static final Pattern TS_QUALIFIER = Pattern.compile("\\b(EMBD|FRQ|OCNL|ISOL|SEV|MOD|OBSC|SQL)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_QUALIFIER = Pattern.compile("\\b(EMBD|FRQ|OCNL|SQL|SEV|MOD|ISOL|OBSC|DVLPG|INTSF|WKN)\\b",
            Pattern.CASE_INSENSITIVE);

    private String parseQualifier(String body, HazardType hazard) {
        if (HazardType.TS == hazard) {
            return closestQualifierToTs(body);
        }
        Matcher qm = ANY_QUALIFIER.matcher(body);
        if (qm.find()) return qm.group(1).toUpperCase();
        return null;
    }

    /** 找到离 TS 最近的强度修饰词 */
    private String closestQualifierToTs(String body) {
        Matcher ts = Pattern.compile("\\bTS\\b", Pattern.CASE_INSENSITIVE).matcher(body);
        if (!ts.find()) {
            // 无 TS 关键词时，返回第一个匹配项
            Matcher qm = ANY_QUALIFIER.matcher(body);
            return qm.find() ? qm.group(1).toUpperCase() : null;
        }
        int tsPos = ts.start();
        Matcher qm = TS_QUALIFIER.matcher(body);
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
            // 回退到 DVLPG/INTSF/WKN（如 "DVLPG AREA TS"）
            Matcher any = ANY_QUALIFIER.matcher(body);
            if (any.find()) return any.group(1).toUpperCase();
            // AREA TS 等没有明确修饰词但 TS 存在
            if (Pattern.compile("\\b(?:AREA\\s+)?TS\\b", Pattern.CASE_INSENSITIVE).matcher(body).find()) {
                return "";
            }
            return null;
        }
        return closest;
    }

    private void parseLevels(SigmetFeature feature, String body) {
        Matcher top = TOPS_TO.matcher(body);
        if (top.find()) {
            String fl = top.group(1);
            if (fl.length() == 4) fl = fl.substring(0, 3);
            feature.top = Integer.parseInt(fl) * 100;
        }
        feature.base = 0;
    }

    private void parseMovement(SigmetFeature feature, String body) {
        Matcher mov = MOV_FROM.matcher(body);
        if (mov.find()) {
            feature.dir = mov.group(1);
            feature.spd = mov.group(2).replaceFirst("^0+(?!$)", "");
            return;
        }
        if (Pattern.compile("\\bSTNR\\b", Pattern.CASE_INSENSITIVE).matcher(body).find()
                || Pattern.compile("\\bMOV\\s+LTL\\b", Pattern.CASE_INSENSITIVE).matcher(body).find()) {
            feature.dir = "-";
            feature.spd = "0";
        }
    }

    /** 解析 FROM 航路点链，生成 Polygon */
    private Polygon parseFromChain(String body) {
        // 提取 FROM ... 到行尾/关键词之间的内容
        Pattern fromPattern = Pattern.compile("FROM\\s+(.+?)(?=\\n[A-Z]|\\bDVLPG\\b|\\bINTSF\\b|\\bWKN\\b|\\bMOV\\b|\\bTOPS?\\b|\\.|$)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher fm = fromPattern.matcher(body);
        if (!fm.find()) return null;

        String chain = fm.group(1).trim();
        // 按 - 分隔航路点
        String[] waypoints = chain.split("\\s*-\\s*");
        List<Coordinate> coords = new ArrayList<>();

        for (String wp : waypoints) {
            Coordinate coord = parseWaypoint(wp.trim());
            if (coord != null) coords.add(coord);
        }

        if (coords.size() < 3) return null;

        // 闭合多边形
        Coordinate first = coords.get(0);
        Coordinate last = coords.get(coords.size() - 1);
        if (!first.equals2D(last)) {
            coords.add(new Coordinate(first));
        }

        return geometryFactory.createPolygon(coords.toArray(new Coordinate[0]));
    }

    /** 解析单个航路点，支持 "10ENE HVE" 和裸 "DTA" 两种格式 */
    private Coordinate parseWaypoint(String wp) {
        if (wp.isEmpty()) return null;
        // 尝试完整格式：距离 + 方位 + VOR ID
        Matcher wm = WAYPOINT.matcher(wp);
        if (wm.find()) {
            String distStr = wm.group(1);
            String bearingCode = wm.group(2);
            String navaidId = wm.group(3);
            return computeCoordinate(navaidId, distStr, bearingCode);
        }
        // 尝试裸 VOR ID（无距离方位）
        if (BARE_VOR.matcher(wp).matches()) {
            return computeCoordinate(wp, null, null);
        }
        return null;
    }

    private Coordinate computeCoordinate(String navaidId, String distStr, String bearingCode) {
        double[] pos = navaidRepo.find(navaidId);
        if (pos == null) return null;
        double lat = pos[0];
        double lon = pos[1];
        if (distStr != null && !distStr.isEmpty()) {
            double distNm = Double.parseDouble(distStr);
            Double bearingDeg = BEARING.get(bearingCode);
            if (bearingDeg != null) {
                double[] offset = offsetCoordinate(lat, lon, distNm, bearingDeg);
                lat = offset[0];
                lon = offset[1];
            }
        }
        return new Coordinate(CoordinateParser.round3(lon), CoordinateParser.round3(lat));
    }

    /** 球面偏移计算：给定起点、距离(NM)、方位(度)，返回 [lat, lon] */
    static double[] offsetCoordinate(double lat, double lon, double distanceNm, double bearingDeg) {
        double lat1 = Math.toRadians(lat);
        double lon1 = Math.toRadians(lon);
        double bearing = Math.toRadians(bearingDeg);
        double angularDist = Math.toRadians(distanceNm / 60.0);

        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(angularDist)
                + Math.cos(lat1) * Math.sin(angularDist) * Math.cos(bearing));
        double lon2 = lon1 + Math.atan2(Math.sin(bearing) * Math.sin(angularDist) * Math.cos(lat1),
                Math.cos(angularDist) - Math.sin(lat1) * Math.sin(lat2));

        return new double[]{CoordinateParser.round3(Math.toDegrees(lat2)),
                            CoordinateParser.round3(Math.toDegrees(lon2))};
    }

    private Date toDate(String ddhhmm, LocalDate baseDate) {
        int day = Integer.parseInt(ddhhmm.substring(0, 2));
        int hour = Integer.parseInt(ddhhmm.substring(2, 4));
        int minute = Integer.parseInt(ddhhmm.substring(4, 6));
        LocalDate date = baseDate.withDayOfMonth(Math.min(day, baseDate.lengthOfMonth()));
        if (day > baseDate.getDayOfMonth() + 15) {
            date = date.minusMonths(1);
        } else if (day < baseDate.getDayOfMonth() - 15) {
            date = date.plusMonths(1);
        }
        return Date.from(date.atTime(hour, minute).toInstant(ZoneOffset.UTC));
    }
}
