package com.siniswift.efb.sigmet;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinateParserTest {
    @Test
    void parsesSeparatedCoordinates() {
        List<Coordinate> coordinates = new CoordinateParser().parsePairs("N1001 W10428 - S0345 E01430");
        assertEquals(-104.467, coordinates.get(0).x, 0.001);
        assertEquals(10.017, coordinates.get(0).y, 0.001);
        assertEquals(14.5, coordinates.get(1).x, 0.001);
        assertEquals(-3.75, coordinates.get(1).y, 0.001);
    }

    @Test
    void parsesCompactCoordinates() {
        List<Coordinate> coordinates = new CoordinateParser().parsePairs("N1211W11146 - N1347W10633");
        assertEquals(-111.767, coordinates.get(0).x, 0.001);
        assertEquals(12.183, coordinates.get(0).y, 0.001);
        assertEquals(-106.55, coordinates.get(1).x, 0.001);
        assertEquals(13.783, coordinates.get(1).y, 0.001);
    }
}
