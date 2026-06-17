package com.siniswift.efb.sigmet;

import java.util.regex.Pattern;

public enum HazardType {
    VA(Pattern.compile("\\bVA\\s+(?:ERUPTION|CLD)\\b", Pattern.CASE_INSENSITIVE)),
    TC(Pattern.compile("\\bTC\\s+[A-Z][A-Z0-9 ]+\\b", Pattern.CASE_INSENSITIVE)),
    TS(Pattern.compile("\\b(?:EMBD|FRQ|OCNL|ISOL|SEV|MOD|OBSC)\\s+TS\\w*\\b|\\bTS\\w*\\s+(?:OBS|FCST)\\b", Pattern.CASE_INSENSITIVE)),
    TURB(Pattern.compile("\\b(?:LGT|MOD|SEV|EXTRM\\s+)?TURB\\b", Pattern.CASE_INSENSITIVE)),
    ICE(Pattern.compile("\\b(?:LGT|MOD|SEV|EXTRM\\s+)?ICE\\b(?!\\s+TS)", Pattern.CASE_INSENSITIVE)),
    MTW(Pattern.compile("\\b(?:LGT|MOD|SEV|EXTRM\\s+)?MTW\\b", Pattern.CASE_INSENSITIVE));

    private final Pattern detectionPattern;

    HazardType(Pattern detectionPattern) {
        this.detectionPattern = detectionPattern;
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
