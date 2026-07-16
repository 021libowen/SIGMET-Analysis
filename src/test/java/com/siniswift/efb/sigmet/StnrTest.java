package com.siniswift.efb.sigmet;

import java.time.LocalDateTime;

public class StnrTest {
    public static void main(String[] args) throws Exception {
        SigmetParser parser = new SigmetParser();
        String raw = "WSCU31 MUHA 032336\nMUFH SIGMET 7 VALID 032335/040335 MUHA-\nMUFH HABANA FIR EMBD TS OBS AT 2325Z WI N2400 W08600 N2200 W08500\nN2000 W07700 N2106 W07419 N2400 W08300 TO N2400 W08600 CB TOP FL490\nMOV STNR INTSF=";
        String result = new GeoJsonWriter().write(parser.parse(raw, "test"));
        // Check if dir and spd are correct
        System.out.println("STNR result: " + (result.contains("\"dir\":\"-\"") ? "dir OK" : "dir WRONG"));
        System.out.println("STNR result: " + (result.contains("\"spd\":\"0\"") ? "spd OK" : "spd WRONG"));

        String raw2 = "WSCU31 MUHA 032336\nMUFH SIGMET 7 VALID 032335/040335 MUHA-\nMUFH HABANA FIR EMBD TS OBS AT 2325Z WI N2400 W08600 N2200 W08500\nN2000 W07700 N2106 W07419 N2400 W08300 TO N2400 W08600 CB TOP FL490\nMOV STRNY WKN=";
        String result2 = new GeoJsonWriter().write(parser.parse(raw2, "test2"));
        System.out.println("STRNY result: " + (result2.contains("\"dir\":\"-\"") ? "dir OK" : "dir WRONG"));
        System.out.println("STRNY result: " + (result2.contains("\"spd\":\"0\"") ? "spd OK" : "spd WRONG"));
    }
}
