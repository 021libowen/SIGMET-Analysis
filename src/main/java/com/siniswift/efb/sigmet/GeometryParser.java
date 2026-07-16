package com.siniswift.efb.sigmet;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GeometryParser {
    private static final Pattern WID_LINE = Pattern.compile("(?:WI\\s+)?(\\d+)NM\\s+WID(?:E)?\\s+LINE\\s+(?:BTN\\s+)?(.+?)(?=\\bCB\\b|\\bTOP\\b|\\bMOV\\b|\\bNC\\b|=|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WI_CIRCLE = Pattern.compile("WI\\s+(\\d+)NM\\s+OF\\s+([NS]\\s*\\d{2,4}(?:\\.\\d+)?\\s*[EW]\\s*\\d{3,5}(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OF_LINE = Pattern.compile("\\b(NW|NE|SW|SE|N|S|E|W)\\s+OF\\s+LINE\\s+(.+?)(?=\\bTOP\\b|\\bFL\\d|\\bSFC\\b|\\bMOV\\b|\\bNC\\b|=|$|\\b[NSEW]{1,2}\\s+OF\\s+LINE\\b)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LAT_SIDE = Pattern.compile("\\b(N|S)\\s+OF\\s+([NS]\\s*\\d{2,4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LON_SIDE = Pattern.compile("\\b(E|W)\\s+OF\\s+([EW]\\s*\\d{3,5})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WI = Pattern.compile("\\bWI\\s+(.+?)(?=\\bCB\\b|\\bTOP\\b|\\bSFC/|\\bFL\\d|\\bFCST\\b|\\bMOV\\b|\\bNC\\b|=|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WI_VA = Pattern.compile("\\bWI\\s+(.+?)(?=\\bWI\\b|\\bCB\\b|\\bTOP\\b|\\bMOV\\b|\\bNC\\b|=|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PSN = Pattern.compile("\\bPSN\\s+([NS]\\s*\\d{2,4}\\s*[EW]\\s*\\d{3,5})", Pattern.CASE_INSENSITIVE);

    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final CoordinateParser coordinateParser = new CoordinateParser();
    private final FirBoundaryRepository firBoundaryRepository;

    GeometryParser(FirBoundaryRepository firBoundaryRepository) {
        this.firBoundaryRepository = firBoundaryRepository;
    }

    Optional<Geometry> parse(String text, String firId, HazardType hazard, String dir) {
        String normalized = text.replace("\r", " ");
        Matcher widLine = WID_LINE.matcher(normalized);
        if (widLine.find()) {
            List<Coordinate> points = coordinateParser.parsePairs(widLine.group(2));
            if (points.size() >= 2) {
                double degrees = Integer.parseInt(widLine.group(1)) / 60.0 / 2.0;
                Geometry corridor = wideLinePolygon(points, degrees);
                Geometry clipped = intersectBoundary(corridor, firId);
                return Optional.of(fixRingCount(clipped.isEmpty() ? corridor : clipped));
            }
        }

        Matcher wiCircle = WI_CIRCLE.matcher(normalized);
        if (wiCircle.find()) {
            List<Coordinate> points = coordinateParser.parsePairs(wiCircle.group(2));
            if (!points.isEmpty()) {
                double radiusDeg = Integer.parseInt(wiCircle.group(1)) / 60.0;
                Geometry circle = geodesicCircle(points.get(0), radiusDeg);
                return Optional.of(fixRingCount(circle));
            }
        }

        // VA hazard: 遍历所有 WI 段（含 OBS + FCST），合并为 MultiPolygon
        if (HazardType.VA == hazard) {
            Matcher wiVa = WI_VA.matcher(normalized);
            List<Polygon> vaPolygons = new ArrayList<>();
            while (wiVa.find()) {
                List<Coordinate> points = coordinateParser.parsePairs(wiVa.group(1));
                if (points.size() >= 3) {
                    Geometry g = polygon(points);
                    collectPolygons(intersectBoundary(g, firId), vaPolygons);
                }
            }
            if (!vaPolygons.isEmpty()) {
                if (vaPolygons.size() == 1) {
                    return Optional.of(fixRingCount(vaPolygons.get(0)));
                }
                return Optional.of(fixRingCount(geometryFactory.createMultiPolygon(vaPolygons.toArray(new Polygon[0]))));
            }
            // VA 无 WI 时 fall through 到 PSN
        }

        Matcher wi = WI.matcher(normalized);
        if (wi.find()) {
            List<Coordinate> points = coordinateParser.parsePairs(wi.group(1));
            if (points.size() >= 3) {
                return Optional.of(fixRingCount(polygon(points)));
            }
        }

        List<Coordinate> allPoints = coordinateParser.parsePairs(normalized);
        if (allPoints.size() >= 3 && !OF_LINE.matcher(normalized).find() && !LAT_SIDE.matcher(normalized).find() && !LON_SIDE.matcher(normalized).find()) {
            return Optional.of(fixRingCount(polygon(allPoints)));
        }

        Matcher ofLine = OF_LINE.matcher(normalized);
        List<Polygon> ofLinePolygons = new ArrayList<>();
        while (ofLine.find()) {
            List<Coordinate> points = coordinateParser.parsePairs(ofLine.group(2));
            if (points.size() >= 2) {
                String ofLineDir = ofLine.group(1);
                String useDir = (ofLineDir != null && !ofLineDir.isEmpty()) ? ofLineDir : dir;
                Geometry halfPlane = directionalHalfPlane(useDir.toUpperCase(), points);
                if (!halfPlane.isEmpty()) {
                    collectPolygons(intersectBoundary(halfPlane, firId), ofLinePolygons);
                }
            }
        }
        if (!ofLinePolygons.isEmpty()) {
            if (ofLinePolygons.size() == 1) {
                return Optional.of(fixRingCount(ofLinePolygons.get(0)));
            }
            return Optional.of(fixRingCount(geometryFactory.createMultiPolygon(ofLinePolygons.toArray(new Polygon[0]))));
        }

        // LAT_SIDE 和 LON_SIDE 可能并存（如 N OF N50 AND W OF E054），取交集
        Geometry sideGeom = null;
        Matcher latSide = LAT_SIDE.matcher(normalized);
        if (latSide.find()) {
            Double lat = coordinateParser.parseLatitudeConstraint(latSide.group(2));
            if (lat != null) {
                sideGeom = latHalfPlane(latSide.group(1).toUpperCase(), lat);
            }
        }
        Matcher lonSide = LON_SIDE.matcher(normalized);
        if (lonSide.find()) {
            Double lon = coordinateParser.parseLongitudeConstraint(lonSide.group(2));
            if (lon != null) {
                Geometry lonGeom = lonHalfPlane(lonSide.group(1).toUpperCase(), lon);
                sideGeom = sideGeom != null ? sideGeom.intersection(lonGeom) : lonGeom;
            }
        }
        if (sideGeom != null) {
            return Optional.of(fixRingCount(intersectBoundary(sideGeom, firId)));
        }

        Matcher psn = PSN.matcher(normalized);
        if (HazardType.VA == hazard && psn.find()) {
            List<Coordinate> points = coordinateParser.parsePairs(psn.group(1));
            if (!points.isEmpty()) {
                return Optional.of(fixRingCount(geodesicCircle(points.get(0), 0.83)));
            }
        }

        // 只有一个孤立坐标点时，以该点为圆心画 0.5° 半径圆
        if (allPoints.size() == 1) {
            return Optional.of(fixRingCount(geodesicCircle(allPoints.get(0), 0.83)));
        }

        // 无匹配时用 FIR 边界兜底
        if (hasFirBoundary(firId)) {
            return Optional.of(fixRingCount(firBoundaryRepository.find(firId)));
        }
        return Optional.empty();
    }

    boolean hasFirBoundary(String firId) {
        return firBoundaryRepository.hasBoundary(firId);
    }

    private void collectPolygons(Geometry geometry, List<Polygon> out) {
        if (geometry instanceof Polygon) {
            out.add((Polygon) geometry);
        } else if (geometry instanceof MultiPolygon) {
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                out.add((Polygon) geometry.getGeometryN(i));
            }
        }
    }

    private Geometry intersectBoundary(Geometry geometry, String firId) {
        if (geometry.isEmpty()) {
            return geometry;
        }
        Geometry boundary = firBoundaryRepository.find(firId);
        try {
            return boundary.intersection(geometry);
        } catch (RuntimeException ex) {
            try {
                return boundary.buffer(0).intersection(geometry.buffer(0));
            } catch (RuntimeException ignored) {
                return geometry;
            }
        }
    }

    /** 确保 Polygon/MultiPolygon 所有环至少 4 个坐标点（不足则补首点闭合） */
    private Geometry fixRingCount(Geometry geometry) {
        if (geometry instanceof Polygon) {
            return fixPolygonRing((Polygon) geometry);
        }
        if (geometry instanceof MultiPolygon) {
            Polygon[] fixed = new Polygon[geometry.getNumGeometries()];
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                fixed[i] = fixPolygonRing((Polygon) geometry.getGeometryN(i));
            }
            return geometryFactory.createMultiPolygon(fixed);
        }
        return geometry;
    }

    private Polygon fixPolygonRing(Polygon polygon) {
        LinearRing shell = ensureRingClosed(polygon.getExteriorRing());
        if (shell == null) {
            return polygon;
        }
        LinearRing[] holes = null;
        if (polygon.getNumInteriorRing() > 0) {
            List<LinearRing> holeList = new ArrayList<>();
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                LinearRing fixed = ensureRingClosed(polygon.getInteriorRingN(i));
                if (fixed != null) {
                    holeList.add(fixed);
                }
            }
            if (!holeList.isEmpty()) {
                holes = holeList.toArray(new LinearRing[0]);
            }
        }
        return geometryFactory.createPolygon(shell, holes);
    }

    private LinearRing ensureRingClosed(LineString ring) {
        Coordinate[] coords = ring.getCoordinates();
        if (coords.length >= 4) {
            return (LinearRing) ring;
        }
        if (coords.length < 2) {
            return null;
        }
        Coordinate[] closed = new Coordinate[coords.length + 1];
        System.arraycopy(coords, 0, closed, 0, coords.length);
        closed[coords.length] = new Coordinate(coords[0]);
        return geometryFactory.createLinearRing(closed);
    }

    private LineString line(List<Coordinate> points) {
        return geometryFactory.createLineString(points.toArray(new Coordinate[0]));
    }

    private Geometry wideLinePolygon(List<Coordinate> points, double halfWidthDegrees) {
        if (points.size() == 2) {
            return wideSegment(points.get(0), points.get(1), halfWidthDegrees);
        }
        Geometry result = wideSegment(points.get(0), points.get(1), halfWidthDegrees);
        for (int i = 1; i < points.size() - 1; i++) {
            result = result.union(wideSegment(points.get(i), points.get(i + 1), halfWidthDegrees));
        }
        return result;
    }

    private Geometry wideSegment(Coordinate a, Coordinate b, double halfWidthDegrees) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length == 0) {
            return geometryFactory.createPoint(a).buffer(halfWidthDegrees, 4);
        }
        double nx = -dy / length * halfWidthDegrees;
        double ny = dx / length * halfWidthDegrees;
        return geometryFactory.createPolygon(new Coordinate[]{
                new Coordinate(a.x + nx, a.y + ny),
                new Coordinate(a.x - nx, a.y - ny),
                new Coordinate(b.x - nx, b.y - ny),
                new Coordinate(b.x + nx, b.y + ny),
                new Coordinate(a.x + nx, a.y + ny)
        });
    }

    private Geometry polygon(List<Coordinate> points) {
        List<Coordinate> normalized = normalizeAntimeridian(points);
        List<Coordinate> closed = new ArrayList<>(normalized);
        Coordinate first = closed.get(0);
        Coordinate last = closed.get(closed.size() - 1);
        if (!first.equals2D(last)) {
            closed.add(new Coordinate(first));
        }
        return geometryFactory.createPolygon(closed.toArray(new Coordinate[0]));
    }

    /** 当坐标跨 180° 经线时，将少数侧归一化到多数侧，避免 JTS 多边形覆盖全球 */
    private List<Coordinate> normalizeAntimeridian(List<Coordinate> points) {
        if (points.size() < 2) return points;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        int positive = 0, negative = 0;
        for (Coordinate c : points) {
            double x = c.x;
            if (x < min) min = x;
            if (x > max) max = x;
            if (x >= 0) positive++;
            else negative++;
        }
        // 经度跨度小于 180° 无需处理
        if (max - min <= 180.0) return points;
        // 决定归一化方向：少数服从多数
        boolean normalizeNegatives = negative < positive;
        List<Coordinate> normalized = new ArrayList<>(points.size());
        for (Coordinate c : points) {
            if (normalizeNegatives && c.x < 0) {
                normalized.add(new Coordinate(CoordinateParser.round3(c.x + 360), c.y));
            } else if (!normalizeNegatives && c.x >= 0) {
                normalized.add(new Coordinate(CoordinateParser.round3(c.x - 360), c.y));
            } else {
                normalized.add(c);
            }
        }
        return normalized;
    }

    private Geometry directionalHalfPlane(String direction, List<Coordinate> line) {
        String upper = direction.toUpperCase();
        // 分解三字母方向如 SSW → S + SW，生成两个半平面取并集
        if (upper.length() == 3 && "NENWSESW".contains(upper.substring(1, 3))) {
            String first = upper.substring(0, 1);
            String second = upper.substring(1, 3);
            List<Geometry> parts = new ArrayList<>();
            addHalfPlanes(first, line, parts);
            addHalfPlanes(second, line, parts);
            if (parts.isEmpty()) return geometryFactory.createEmpty(2);
            return unionParts(parts);
        }
        List<Geometry> parts = new ArrayList<>();
        addHalfPlanes(direction, line, parts);
        if (parts.isEmpty()) return geometryFactory.createEmpty(2);
        return unionParts(parts);
    }

    private void addHalfPlanes(String direction, List<Coordinate> line, List<Geometry> parts) {
        for (int i = 0; i < line.size() - 1; i++) {
            Coordinate a = line.get(i);
            Coordinate b = line.get(i + 1);
            Geometry part = singleLineHalfPlane(direction, a, b);
            if (!part.isEmpty()) {
                parts.add(part);
            }
//            // 两字母 intercardinal 方向：按线段走向决定是否拆分 cardinal 半平面
//            // NW-SE 走向：NE/SW（垂直）→ 两个 cardinal 都画；SE/NW（沿线）→ 只画 E 或 W
//            // NE-SW 走向：SE/NW（垂直）→ 两个 cardinal 都画；NE/SW（沿线）→ 只画 E 或 W
//            if (direction.length() == 2) {
//                double dx = b.x - a.x;
//                double dy = b.y - a.y;
//                boolean nwSe = dx * dy < 0;  // NW-SE 走向（dx,dy 异号）
//                boolean neSw = dx * dy > 0;  // NE-SW 走向（dx,dy 同号）
//                switch (direction.toUpperCase()) {
//                    case "NE":
//                        if (nwSe) { addBothCardinals("N", "E", a, b, parts); }
//                        else if (neSw) { addCardinal("E", a, b, parts); }
//                        break;
//                    case "SW":
//                        if (nwSe) { addBothCardinals("S", "W", a, b, parts); }
//                        else if (neSw) { addCardinal("W", a, b, parts); }
//                        break;
//                    case "SE":
//                        if (nwSe) { addCardinal("E", a, b, parts); }
//                        else if (neSw) { addBothCardinals("S", "E", a, b, parts); }
//                        break;
//                    case "NW":
//                        if (nwSe) { addCardinal("W", a, b, parts); }
//                        else if (neSw) { addBothCardinals("N", "W", a, b, parts); }
//                        break;
//                }
//            }
        }
    }

    private void addBothCardinals(String c1, String c2, Coordinate a, Coordinate b, List<Geometry> parts) {
        Geometry g1 = singleLineHalfPlane(c1, a, b);
        if (!g1.isEmpty()) parts.add(g1);
        Geometry g2 = singleLineHalfPlane(c2, a, b);
        if (!g2.isEmpty()) parts.add(g2);
    }

    private void addCardinal(String cardinal, Coordinate a, Coordinate b, List<Geometry> parts) {
        Geometry g = singleLineHalfPlane(cardinal, a, b);
        if (!g.isEmpty()) parts.add(g);
    }

    private Geometry unionParts(List<Geometry> parts) {
        Geometry result = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            try {
                result = result.union(parts.get(i));
            } catch (RuntimeException ex) {
                result = safeUnion(result, parts.get(i));
            }
        }
        return result;
    }

    private Geometry safeUnion(Geometry a, Geometry b) {
        try {
            return a.buffer(0).union(b.buffer(0));
        } catch (RuntimeException ex) {
            return a;
        }
    }

    private Geometry singleLineHalfPlane(String direction, Coordinate a, Coordinate b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length == 0) {
            return rectangle(-180, -90, 180, 90);
        }
        double dirX = 0, dirY = 0;
        switch (direction.toUpperCase()) {
            case "N":  dirX = 0;    dirY = 1;    break;
            case "S":  dirX = 0;    dirY = -1;   break;
            case "E":  dirX = 1;    dirY = 0;    break;
            case "W":  dirX = -1;   dirY = 0;    break;
            case "NE": dirX = 1;    dirY = 1;    break;
            case "NW": dirX = -1;   dirY = 1;    break;
            case "SE": dirX = 1;    dirY = -1;   break;
            case "SW": dirX = -1;   dirY = -1;   break;
            default:   return rectangle(-180, -90, 180, 90);
        }

        double offset = 30.0;
        Coordinate p1 = new Coordinate(a.x, a.y);
        Coordinate p2 = new Coordinate(b.x, b.y);
        Coordinate aExt = clamp(a.x + dirX * offset, a.y + dirY * offset);
        Coordinate bExt = clamp(b.x + dirX * offset, b.y + dirY * offset);
        Coordinate[] ring = new Coordinate[]{p1, aExt, bExt, p2, new Coordinate(p1)};

        Coordinate[] clean = dedupRing(ring);
        if (clean == null || clean.length < 4) {
            return geometryFactory.createEmpty(2);
        }
        if (isSelfIntersecting(clean)) {
            return geometryFactory.createEmpty(2);
        }
        return geometryFactory.createPolygon(clean);
    }

    /** 去重连续重复坐标，返回闭合环或 null */
    private Coordinate[] dedupRing(Coordinate[] ring) {
        List<Coordinate> list = new ArrayList<>();
        for (Coordinate c : ring) {
            if (list.isEmpty() || !c.equals2D(list.get(list.size() - 1))) {
                list.add(c);
            }
        }
        if (list.size() < 4) {
            return null;
        }
        return list.toArray(new Coordinate[0]);
    }

    /** 检查环是否存在自相交（非相邻边相交） */
    private boolean isSelfIntersecting(Coordinate[] ring) {
        int n = ring.length - 1; // ring 已闭合，有效边数 n
        for (int i = 0; i < n; i++) {
            for (int j = i + 2; j < n; j++) {
                // 首尾边（i=0, j=n-1）相邻，跳过
                if (i == 0 && j == n - 1) {
                    continue;
                }
                if (segmentsIntersect(ring[i], ring[i + 1], ring[j], ring[j + 1])) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 判断线段 AB 和 CD 是否相交（不包括端点接触） */
    private boolean segmentsIntersect(Coordinate a, Coordinate b, Coordinate c, Coordinate d) {
        double o1 = cross(a, b, c);
        double o2 = cross(a, b, d);
        double o3 = cross(c, d, a);
        double o4 = cross(c, d, b);
        if (o1 == 0 || o2 == 0 || o3 == 0 || o4 == 0) {
            return false; // 端点共线不算自交
        }
        return (o1 > 0) != (o2 > 0) && (o3 > 0) != (o4 > 0);
    }

    private double cross(Coordinate a, Coordinate b, Coordinate c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    private Geometry latHalfPlane(String side, double lat) {
        return "N".equals(side) ? rectangle(-180, lat, 180, 90) : rectangle(-180, -90, 180, lat);
    }

    private Geometry lonHalfPlane(String side, double lon) {
        return "E".equals(side) ? rectangle(lon, -90, 180, 90) : rectangle(-180, -90, lon, 90);
    }

    private Coordinate clamp(double x, double y) {
        double clampedX = Math.max(-180, Math.min(180, x));
        double clampedY = Math.max(-90, Math.min(90, y));
        return new Coordinate(clampedX, clampedY);
    }

    /** 以球面几何生成真圆（避免平面投影导致的椭圆变形） */
    private Geometry geodesicCircle(Coordinate center, double radiusDeg) {
        double lat = Math.toRadians(center.y);
        double lon = Math.toRadians(center.x);
        double dist = Math.toRadians(radiusDeg);
        double sinLat = Math.sin(lat);
        double cosLat = Math.cos(lat);
        double sinDist = Math.sin(dist);
        double cosDist = Math.cos(dist);
        int n = 36;
        Coordinate[] ring = new Coordinate[n + 1];
        for (int i = 0; i < n; i++) {
            double bearing = Math.toRadians(i * 360.0 / n);
            double newLat = Math.asin(sinLat * cosDist + cosLat * sinDist * Math.cos(bearing));
            double newLon = lon + Math.atan2(Math.sin(bearing) * sinDist * cosLat,
                                             cosDist - sinLat * Math.sin(newLat));
            ring[i] = new Coordinate(CoordinateParser.round3(Math.toDegrees(newLon)),
                                     CoordinateParser.round3(Math.toDegrees(newLat)));
        }
        ring[n] = ring[0];
        return geometryFactory.createPolygon(ring);
    }

    private Geometry rectangle(double minX, double minY, double maxX, double maxY) {
        return geometryFactory.createPolygon(new Coordinate[]{
                new Coordinate(minX, minY), new Coordinate(maxX, minY), new Coordinate(maxX, maxY),
                new Coordinate(minX, maxY), new Coordinate(minX, minY)
        });
    }
}
