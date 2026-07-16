package com.weather.sigmet;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public final class SigmetParser {
    private static final Logger LOG = Logger.getLogger(SigmetParser.class.getName());
    private static final DateTimeFormatter SAMPLE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SigmetBlockSplitter splitter = new SigmetBlockSplitter();
    private final FirBoundaryRepository firBoundaryRepository = new FirBoundaryRepository();
    private final GeometryParser geometryParser = new GeometryParser(firBoundaryRepository);
    private final NavaidRepository navaidRepository = new NavaidRepository();
    private final ConvectiveSigmetParser convectiveParser = new ConvectiveSigmetParser(navaidRepository);
    private final SigmetReportParser reportParser = new SigmetReportParser(geometryParser, convectiveParser);

    public List<SigmetFeature> parse(String sigmetText, String sampleDirectoryName) {
        LocalDateTime sampleTime = LocalDateTime.parse(sampleDirectoryName, SAMPLE_TIME);
        List<SigmetFeature> features = new ArrayList<>();
        for (SigmetBlock block : splitter.split(sigmetText)) {
            try {
                reportParser.parse(block, sampleTime).ifPresent(features::add);
            } catch (Exception e) {
                LOG.warning("报文解析错误: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }
        return features;
    }
}
