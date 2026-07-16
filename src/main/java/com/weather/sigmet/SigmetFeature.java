package com.weather.sigmet;

import java.util.Date;

public final class SigmetFeature {
    public String icaoId;
    public String firId;
    public String firName;
    public String seriesId;
    public HazardType hazard;
    public String qualifier;
    public Date validTimeFrom;
    public Date validTimeTo;
    public Date issued;
    public String category;
    public Integer base;
    public Integer top;
    public String dir;
    public String spd;
    public String chng;
    public String rawSigmet;
    public String geojson;

    long validToEpoch() {
        if (validTimeTo == null) return Long.MAX_VALUE;
        return validTimeTo.getTime();
    }

    long validFromEpoch() {
        if (validTimeFrom == null) return 0;
        return validTimeFrom.getTime();
    }
}
