package com.siniswift.efb.sigmet;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SigmetBlock {
    final HazardType hazard;
    final String raw;

    SigmetBlock(HazardType hazard, String raw) {
        this.hazard = hazard;
        this.raw = raw;
    }
}

final class SigmetBlockSplitter {
    /** 匹配 SIGMET 头行，如 "VABF SIGMET D01 VALID 041000/041400 VABB-" */
    private static final Pattern SIGMET_HEADER = Pattern.compile("^\\S+\\s+SIGMET\\s+\\S+\\s+VALID\\s+\\d{6}/\\d{6}", Pattern.MULTILINE);

    List<SigmetBlock> split(String text) {
        List<SigmetBlock> blocks = new ArrayList<>();
        String[] parts = text.replace("\r\n", "\n").replace('\r', '\n').split("(?m)^-+\\s*$");
        for (String part : parts) {
            String block = part.trim();
            if (block.isEmpty()) {
                continue;
            }
            HazardType hazard = null;
            List<String> lines = new ArrayList<>();
            for (String line : block.split("\n", -1)) {
                if (line.startsWith("Hazard:")) {
                    hazard = HazardType.fromHeader(line.substring("Hazard:".length()).trim());
                } else {
                    lines.add(line);
                }
            }
            String raw = String.join("\n", lines).trim();
            if (!raw.isEmpty()) {
                blocks.addAll(splitBySigmetHeaders(hazard, raw));
            }
        }
        return blocks;
    }

    /** 若 raw 包含多条 SIGMET，按 SIGMET 头拆开，WMO 头行共享 */
    private List<SigmetBlock> splitBySigmetHeaders(HazardType hazard, String raw) {
        String[] rawLines = raw.split("\n", -1);
        // 找出所有 SIGMET 头行的索引
        List<Integer> headerIndices = new ArrayList<>();
        for (int i = 0; i < rawLines.length; i++) {
            if (SIGMET_HEADER.matcher(rawLines[i]).find()) {
                headerIndices.add(i);
            }
        }
        if (headerIndices.size() <= 1) {
            List<SigmetBlock> single = new ArrayList<>();
            single.add(new SigmetBlock(hazard, raw));
            return single;
        }
        // 第一条是 WMO 头行（如 "WSIN90 VABB 041000"），后续 SIGMET 都需要带上它
        String wmoHeader = rawLines[0];
        List<SigmetBlock> result = new ArrayList<>();
        for (int i = 0; i < headerIndices.size(); i++) {
            int start = headerIndices.get(i);
            int end = (i + 1 < headerIndices.size()) ? headerIndices.get(i + 1) : rawLines.length;
            StringBuilder sb = new StringBuilder(wmoHeader).append('\n');
            for (int j = start; j < end; j++) {
                sb.append(rawLines[j]);
                if (j < end - 1) sb.append('\n');
            }
            result.add(new SigmetBlock(hazard, sb.toString().trim()));
        }
        return result;
    }
}
