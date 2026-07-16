package com.weather.sigmet;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 从 classpath 加载 navaids.json，提供 VOR/航路点坐标 O(1) 查询。
 */
public final class NavaidRepository {

    private static final Logger LOG = Logger.getLogger(NavaidRepository.class.getName());

    private final Map<String, double[]> navaids;

    public NavaidRepository() {
        Map<String, double[]> map = new HashMap<>();
        // 1) 加载全球 navaids
        try (InputStream input = getClass().getResourceAsStream("/navaids.json")) {
            if (input == null) {
                LOG.warning("navaids.json 未在 classpath 中找到，美国 CONVECTIVE SIGMET 将无法解析");
            } else {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, double[]> raw = mapper.readValue(input,
                        mapper.getTypeFactory().constructMapType(HashMap.class, String.class, double[].class));
                map.putAll(raw);
                LOG.info("navaids.json 加载完成: " + map.size() + " 个台站");
            }
        } catch (Exception e) {
            LOG.warning("加载 navaids.json 失败: " + e.getMessage());
        }
        // 2) 美国台站覆盖（数据库缺失或境外同名站覆盖时修正）
        try (InputStream input = getClass().getResourceAsStream("/navaids-us-override.json")) {
            if (input != null) {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, double[]> overrides = mapper.readValue(input,
                        mapper.getTypeFactory().constructMapType(HashMap.class, String.class, double[].class));
                map.putAll(overrides);
                LOG.info("美国台站覆盖加载完成: " + overrides.size() + " 个");
            }
        } catch (Exception e) {
            LOG.warning("加载 navaids-us-override.json 失败: " + e.getMessage());
        }
        this.navaids = map;
    }

    /** 根据台站 ID 查找坐标，找不到返回 null */
    public double[] find(String codeId) {
        return navaids.get(codeId);
    }
}
