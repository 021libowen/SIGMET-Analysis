package com.weather.sigmet;

import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CoordinateParser {
    private static final Pattern PAIR = Pattern.compile("([NS])\\s*(\\d{2,4}(?:\\.\\d+)?)\\s*([EW])\\s*(\\d{3,5}(?:\\.\\d+)?)");
    private static final Pattern LAT = Pattern.compile("([NS])\\s*(\\d{2,4}(?:\\.\\d+)?)");
    private static final Pattern LON = Pattern.compile("([EW])\\s*(\\d{3,5}(?:\\.\\d+)?)");

    List<Coordinate> parsePairs(String text) {
        String normalized = normalize(text);
        Matcher matcher = PAIR.matcher(normalized);
        List<Coordinate> coordinates = new ArrayList<>();
        while (matcher.find()) {
            double lat = parseLat(matcher.group(1), matcher.group(2));
            double lon = parseLon(matcher.group(3), matcher.group(4));
            coordinates.add(new Coordinate(round3(lon), round3(lat)));
        }
        return coordinates;
    }

    Double parseLatitudeConstraint(String text) {
        Matcher matcher = LAT.matcher(normalize(text));
        if (!matcher.find()) {
            return null;
        }
        return parseLat(matcher.group(1), matcher.group(2));
    }

    Double parseLongitudeConstraint(String text) {
        Matcher matcher = LON.matcher(normalize(text));
        if (!matcher.find()) {
            return null;
        }
        return parseLon(matcher.group(1), matcher.group(2));
    }

    private static String normalize(String text) {
        return text.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ");
    }

    private static double parseLat(String hemisphere, String value) {
        double result = parseDms(value, 2);
        return "S".equals(hemisphere) ? -result : result;
    }

    private static double parseLon(String hemisphere, String value) {
        double result = parseDms(value, 3);
        return "W".equals(hemisphere) ? -result : result;
    }

    private static double parseDms(String value, int degreeDigits) {
        if (value.contains(".")) {
            return Double.parseDouble(value);
        }
        if (value.length() <= degreeDigits) {
            return Double.parseDouble(value);
        }
        int degrees = Integer.parseInt(value.substring(0, degreeDigits));
        int minutes = Integer.parseInt(value.substring(degreeDigits));
        return degrees + minutes / 60.0;
    }

    /** DB 中的完整 DMS 格式：N44503228 → 44°50'32.28" → 44.8423 */
    static double parseDmsDb(String value) {
        char hemi = value.charAt(0);
        int degreeDigits = (hemi == 'N' || hemi == 'S') ? 2 : 3;
        int degrees = Integer.parseInt(value.substring(1, 1 + degreeDigits));
        int minutes = Integer.parseInt(value.substring(1 + degreeDigits, 1 + degreeDigits + 2));
        double seconds = Double.parseDouble(value.substring(1 + degreeDigits + 2)) / 100.0;
        double result = degrees + minutes / 60.0 + seconds / 3600.0;
        if (hemi == 'S' || hemi == 'W') result = -result;
        return round3(result);
    }

    static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
