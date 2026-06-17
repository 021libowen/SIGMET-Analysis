package com.siniswift.efb.sigmet;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class SigmetParser {
    private static final DateTimeFormatter SAMPLE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SigmetBlockSplitter splitter = new SigmetBlockSplitter();
    private final FirBoundaryRepository firBoundaryRepository = new FirBoundaryRepository();
    private final GeometryParser geometryParser = new GeometryParser(firBoundaryRepository);
    private final SigmetReportParser reportParser = new SigmetReportParser(geometryParser);

    public List<SigmetFeature> parse(String sigmetText, String sampleDirectoryName) {
        LocalDateTime sampleTime = LocalDateTime.parse(sampleDirectoryName, SAMPLE_TIME);
        List<SigmetFeature> features = new ArrayList<>();
        for (SigmetBlock block : splitter.split(sigmetText)) {
            try {
                reportParser.parse(block, sampleTime).ifPresent(features::add);
            } catch (Exception e) {
                System.out.println("报文解析错误：【" + block.raw + "】, error: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }
        return features;
    }
}
