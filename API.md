# SIGMET Parser API

**包名**: `com.weather.sigmet`
**JAR**: `sigmet-parser-1.0-SNAPSHOT.jar`（shaded，含 Jackson 2.17.2 + JTS 1.19.0）
**Java 版本**: 1.8

---

## 核心类

| 类 | 可见性 | 说明 |
|---|---|---|
| `SigmetParser` | `public` | 主入口，解析原始 SIGMET 报文 |
| `SigmetFeature` | `public` | 解析结果实体，所有字段 public |
| `GeoJsonWriter` | `public` | Feature 列表 → GeoJSON 序列化 |
| `TimeSlotExporter` | `public` | 按整点时隙分组输出 |
| `HazardType` | `public enum` | 灾害类型：VA, TC, TS, TURB, ICE, MTW |

---

## 1. 解析报文

```java
SigmetParser parser = new SigmetParser();
List<SigmetFeature> features = parser.parse(rawSigmetText, "20260614070000");
// 参数2: 格式 yyyyMMddHHmmss 的时间戳，用于推断有效日期
```

### SigmetFeature 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `icaoId` | `String` | 发报气象台 ICAO 码 |
| `firId` | `String` | 情报区 ID |
| `firName` | `String` | 情报区名称 |
| `seriesId` | `String` | 报文序号 |
| `hazard` | `HazardType` | 灾害类型 |
| `qualifier` | `String` | 限定词（EMBD/FRQ/SEV 等） |
| `validTimeFrom` | `java.time.Instant` | 生效时间（UTC） |
| `validTimeTo` | `java.time.Instant` | 失效时间（UTC） |
| `issued` | `java.time.Instant` | 发布时间（UTC），WMO 报头 DDHHmm |
| `category` | `String` | 大类：Convective / Non-Convective / Volcanic Ash |
| `base` | `Integer` | 底层高（ft），可能 null |
| `top` | `Integer` | 顶层高（ft），可能 null |
| `dir` | `String` | 移动方向（N/S/E/W/NE/.../-） |
| `spd` | `String` | 移动速度（KT 单位） |
| `chng` | `String` | 强度变化（NC/WKN/INTSF） |
| `rawSigmet` | `String` | 原始报文原文 |
| `geometry` | `org.locationtech.jts.geom.Geometry` | JTS 几何对象 |

`properties()` 方法返回 `Map<String, Object>` 适合直接序列化。

---

## 2. 输出 GeoJSON

```java
GeoJsonWriter writer = new GeoJsonWriter();

// 方式1: 直接返回 GeoJSON 字符串
String geojson = writer.write(features);

// 方式2: 返回 Jackson JsonNode 供进一步处理
JsonNode node = writer.toJson(features);

// 方式3: 单条 Feature 转扁平 JSON（属性 + geojson 几何字符串）
String flatJson = writer.writeFlat(feature);
Map<String, Object> flatMap = writer.toFlatMap(feature);
```

`writeFlat` / `toFlatMap` 输出格式：

```json
{
  "icaoId": "RJTD",
  "firId": "RJJJ",
  "firName": "RJJJ FUKUOKA",
  "seriesId": "Q01",
  "hazard": "TURB",
  "qualifier": "SEV",
  "validTimeFrom": "2026-06-14T01:37:00.000Z",
  "validTimeTo": "2026-06-14T03:37:00.000Z",
  "top": 35000,
  "dir": "E",
  "spd": "30",
  "chng": "NC",
  "rawSigmet": "WSJP31 RJTD 140137\n...",
  "geojson": "{\"type\":\"Polygon\",\"coordinates\":[[[...]]]}"
}
```

`write(features)` 输出格式：标准 GeoJSON FeatureCollection。

---

## 3. 按时隙分组

```java
TimeSlotExporter exporter = new TimeSlotExporter();

// 从 GeoJSON 文件反序列化 Feature 列表
List<SigmetFeature> features = TimeSlotExporter.parseFeatures(Paths.get("input.geojson"));

// 按时隙分组输出
LocalDateTime baseTime = LocalDateTime.now(ZoneOffset.UTC);
exporter.export(baseTime, features, Paths.get("output.json"));
```

`export` 逻辑：
1. 从 `baseTime` 找到下一个整点（如 06:21 → 07:00）
2. 生成 6 个连续的整点时隙（07:00, 08:00, ..., 12:00）
3. 筛选各时隙生效的 Feature（`validTimeFrom <= slot < validTimeTo`）
4. 写入 JSON

### 输出格式

```json
[
  {
    "time": "20260614070000",
    "data": [
      {
        "icaoId": "RJTD",
        "firId": "RJJJ",
        "firName": "RJJJ FUKUOKA",
        "seriesId": "Q01",
        "hazard": "TURB",
        "qualifier": "SEV",
        "validTimeFrom": "2026-06-14T01:37:00.000Z",
        "validTimeTo": "2026-06-14T03:37:00.000Z",
        "top": 35000,
        "dir": "E",
        "spd": "30",
        "chng": "NC",
        "rawSigmet": "WSJP31 RJTD 140137\n...",
        "geojson": "{\"type\":\"Polygon\",\"coordinates\":[[[...]]]}"
      }
    ]
  }
]
```

每个 Feature 中 `geojson` 字段是独立的几何 GeoJSON 字符串。

---

## 4. HazardType 枚举

```java
HazardType hazard = HazardType.fromHeader("TS");   // 从 Header 转换
HazardType detected = HazardType.detectFrom(body); // 从报文体检测
```

| 值 | 含义 |
|---|---|
| `VA` | 火山灰 |
| `TC` | 热带气旋 |
| `TS` | 雷暴 |
| `TURB` | 颠簸 |
| `ICE` | 积冰 |
| `MTW` | 山地波 |

---

## Maven 依赖

外部项目引入：

```xml
<dependency>
    <groupId>com.weather</groupId>
    <artifactId>sigmet-parser</artifactId>
    <version>1.0-SNAPSHOT</version>
    <scope>system</scope>
    <systemPath>${project.basedir}/lib/sigmet-parser-1.0-SNAPSHOT.jar</systemPath>
</dependency>
```

JAR 已包含 Jackson 和 JTS，无需额外引入。
