package com.weather.sigmet;

import java.util.regex.Pattern;

public enum HazardType {
    VA(Pattern.compile("\\bVA\\s+(?:ERUPTION|CLD)\\b", Pattern.CASE_INSENSITIVE)),
    TC(Pattern.compile("\\bTC\\s+[A-Z][A-Z0-9 ]+\\b", Pattern.CASE_INSENSITIVE)),
    TS(Pattern.compile("\\b(?:EMBD|FRQ|OCNL|ISOL|SEV|MOD|OBSC)\\s+TS\\w*\\b|\\bTS\\w*\\s+(?:OBS|FCST)\\b|\\bAREA\\s+TS\\b|\\bTS\\s+AREA\\b", Pattern.CASE_INSENSITIVE)),
    TURB(Pattern.compile("\\b(?:LGT|MOD|SEV|EXTRM\\s+)?TURB\\b", Pattern.CASE_INSENSITIVE)),
    ICE(Pattern.compile("\\b(?:LGT|MOD|SEV|EXTRM\\s+)?ICE\\b(?!\\s+TS)", Pattern.CASE_INSENSITIVE)),
    DS(Pattern.compile("\\b(?:HVY|OBSC)\\s+DS\\b|\\bDS\\s+(?:OBS|FCST)\\b", Pattern.CASE_INSENSITIVE)),
    SS(Pattern.compile("\\b(?:HVY|OBSC)\\s+SS\\b|\\bSS\\s+(?:OBS|FCST)\\b", Pattern.CASE_INSENSITIVE)),
    MTW(Pattern.compile("\\b(?:LGT|MOD|SEV|EXTRM\\s+)?MTW\\b", Pattern.CASE_INSENSITIVE)),
    CNL(Pattern.compile("\\bCNL\\s+SIGMET\\b", Pattern.CASE_INSENSITIVE));

    private final Pattern detectionPattern;

    HazardType(Pattern detectionPattern) {
        this.detectionPattern = detectionPattern;
    }

    /** SIGMET 大类 */
    public String getCategory() {
        if (this == VA) return "Volcanic Ash";
        if (this == TS) return "Convective";
        if (this == CNL) return "Cancellation";
        return "Non-Convective";
    }

    /** 从报文头 Hazard: 字段转换，TS 子类型统一为 TS */
    public static HazardType fromHeader(String input) {
        if (input == null || input.isEmpty()) return null;
        return input.startsWith("TS") ? TS : valueOf(input.toUpperCase());
    }

    /** 从报文体关键词推断类型 */
    public static HazardType detectFrom(String body) {
        for (HazardType type : values()) {
            if (type.detectionPattern.matcher(body).find()) {
                return type;
            }
        }
        return null;
    }
}
