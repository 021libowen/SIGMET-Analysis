# sigmet-parser

## 项目概述
SIGMET 气象报文解析器，将原始 SIGMET 报文解析为 GeoJSON 格式输出，并与官方转换结果进行对比校验。

## 技术栈
- Java 8
- Maven 构建
- Jackson 2.17.2（JSON 处理）
- JTS 1.19.0（地理几何处理）
- JUnit 5.10.3（测试）

## 构建与运行
```bash
mvn compile                    # 编译
mvn test                       # 运行测试
mvn package -DskipTests        # 打包（跳过测试）
mvn exec:java -Dexec.mainClass="com.example.sigmet.Main"   # 运行主程序
```

## 项目结构
```
src/main/java/com/example/sigmet/
  Main.java              — 入口，批量解析并比较
  SigmetParser.java      — 顶层解析器，依赖子组件
  SigmetBlockSplitter.java — 原始报文分块（split header block）
  SigmetReportParser.java  — 单条 SIGMET 报告解析（正则提取属性）
  GeometryParser.java    — 几何坐标解析（POLYGON/LINE/AREA 等）
  CoordinateParser.java  — 坐标格式转换（DDMMSS → 十进制）
  FirBoundaryRepository.java — FIR 边界数据（用于裁剪）
  SigmetFeature.java     — 解析结果实体（属性 + Geometry）
  GeoJsonWriter.java     — Feature → GeoJSON 序列化
  GeoJsonComparator.java — GeoJSON 按索引逐个比较
src/test/.../
data/                    — 测试数据（每目录包含 sigmet.txt + sigmet-geojson.txt）
```

## 注意事项
- `SigmetFeature.properties()` 生成 JSON 属性时，null 值会被跳过，导致部分属性可能缺失
- `validTimeFrom/validTimeTo` 使用 `.000Z` 后缀（非标准 ISO 格式），比较时需注意
- 原始报文编码为 UTF-8
- `FirBoundaryRepository` 内置了 WMFC 等 FIR 边界硬编码坐标，用于裁剪跨边界几何
- 编译目标为 Java 8，不要使用 JDK 9+ API
