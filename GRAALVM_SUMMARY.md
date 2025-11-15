# GraalVM Native Image 支援評估 - 執行摘要

## 問題

如何將 hi-sql 專案改造為支援 GraalVM Native Image，並像 Spring Boot JPA 一樣**不需要手動維護反射配置**？

---

## 結論

✅ **可行**，推薦採用 **Spring Boot 3.x + AOT 處理器**方案

---

## 核心挑戰

### 當前架構的 GraalVM 兼容性問題

| 問題類型 | 影響範圍 | 嚴重程度 |
|---------|---------|---------|
| **運行時反射** | 實體類字段訪問 | 🔴 高 |
| **動態代理** | Repository 介面實現 | 🔴 高 |
| **資源加載** | XML SQL 定義 | 🟡 中 |
| **策略實例化** | PaginationMode, NamingStrategy | 🟡 中 |

### 主要反射使用場景

```
hi-sql 反射使用分析:
├─ ClassUtil.java (L28-34)        → 實體類字段掃描
├─ SQLStoreFactory.java (L67)     → 字段讀寫操作
├─ FieldColumnFactory.java (L47)  → JPA 註解處理
├─ SQLMethodFactory.java (L71)    → Repository 方法掃描
├─ SQLRepositoryFactoryBean (L54) → JDK 動態代理創建
└─ SQLInjector.java (L36)         → XML 資源加載
```

---

## 推薦方案

### 策略: Spring Boot 3 + AOT 處理器

#### 核心機制

```
構建時 (AOT Phase)
    ↓
掃描 Repository 介面 + @Entity 類
    ↓
自動生成反射配置
    ↓
打包進 Native Image
    ↓
運行時無需反射 API
```

#### 技術架構

```java
// 1. 實現 AOT 處理器
public class HiSqlRuntimeHints implements BeanFactoryInitializationAotProcessor {
    public void processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
        // 自動掃描並註冊:
        // - 所有 @Repository 介面 → 代理配置
        // - 所有 @Entity 類 → 反射配置
        // - 所有 XML 文件 → 資源配置
    }
}

// 2. 註冊到 Spring AOT 引擎
// META-INF/spring/aot.factories
org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor=\
pers.clare.hisql.aot.HiSqlRuntimeHints
```

#### 用戶使用體驗

**改造前（手動配置）**:
```json
// reflect-config.json - 需手動維護
{
  "name": "com.example.User",
  "allDeclaredFields": true,
  "allDeclaredConstructors": true
}
```

**改造後（零配置）**:
```java
@Configuration
@EnableHiSql(basePackages = "com.example")
public class HiSqlConfig {
    // 自動掃描所有 Entity 和 Repository
    // 構建時自動生成所有反射配置！
}

// 或使用註解
@HiSqlEntity  // 自動註冊反射
@Entity
public class User {
    @Id private Long id;
    private String name;
}
```

**構建 Native Image**:
```bash
mvn -Pnative native:compile
# 零手動配置，一鍵構建！
```

---

## 實施計劃

### 時間表

| 階段 | 任務 | 工時 | 時間 |
|------|------|------|------|
| **階段 1** | 升級 Spring Boot 3.x | 40h | 週 1-2 |
| **階段 2** | 實現 AOT 處理器 | 60h | 週 3-5 |
| **階段 3** | 用戶便利功能 | 24h | 週 5 |
| **階段 4** | 掃描機制優化 | 32h | 週 6 |
| **階段 5** | XML 資源處理 | 16h | 週 6 |
| **階段 6** | 測試與驗證 | 48h | 週 7-8 |
| **階段 7** | 文檔與發布 | 20h | 週 8 |
| **總計** | | **240h** | **8 週** |

### 里程碑

```
Week 1-2: Spring Boot 3 升級完成
    ├─ javax → jakarta 遷移
    ├─ 依賴更新
    └─ 編譯通過

Week 3-5: AOT 核心實現
    ├─ HiSqlRuntimeHints 完成
    ├─ Repository 自動掃描
    ├─ Entity 自動掃描
    └─ 策略類註冊

Week 6: 優化與增強
    ├─ @HiSqlEntity 註解
    ├─ XML 資源處理
    └─ 掃描性能優化

Week 7-8: 質量保證
    ├─ Native Image 測試
    ├─ 性能基準測試
    ├─ 文檔完善
    └─ 發布 v2.0.0
```

---

## 技術收益

### 性能提升

| 指標 | JVM 模式 | Native Image | 提升 |
|------|---------|--------------|------|
| **啟動時間** | ~3-5 秒 | ~0.05 秒 | **60-100x** |
| **內存占用** | ~200-300 MB | ~30-50 MB | **5-10x** |
| **首次請求** | ~500ms | ~10ms | **50x** |
| **鏡像大小** | ~100 MB (JAR) | ~50 MB | **2x** |

### 部署優勢

✅ **容器化**: 更小的 Docker 鏡像
✅ **雲原生**: 適合 Serverless/FaaS
✅ **資源效率**: 降低雲服務成本
✅ **快速擴展**: 秒級冷啟動

---

## 風險評估

### 主要風險

| 風險 | 概率 | 影響 | 緩解措施 |
|------|------|------|---------|
| Spring Boot 3 升級兼容性 | 🟡 中 | 🔴 高 | 完整測試套件 |
| AOT 處理器複雜性 | 🟡 中 | 🟡 中 | 手動註冊後備方案 |
| 動態 SQL 限制 | 🟢 低 | 🟡 中 | 文檔化不支持模式 |
| 性能回歸 | 🟢 低 | 🟡 中 | 性能基準測試 |

### 破壞性變更

⚠️ **不兼容的變更**:
- 需要 Java 17+（當前 Java 11）
- Spring Boot 3.x（當前 2.5.6）
- `javax.persistence` → `jakarta.persistence`

**建議**: 發布為主版本號升級（v2.0.0）

---

## 替代方案比較

| 方案 | 自動化程度 | 維護成本 | 用戶體驗 | 工作量 |
|------|-----------|---------|---------|--------|
| **A. Spring Boot 3 + AOT** | ⭐⭐⭐⭐⭐ | 低 | 優秀 | 240h |
| B. 手動配置 | ⭐ | 高 | 差 | 40h |
| C. 編譯時代碼生成 | ⭐⭐⭐⭐⭐ | 中 | 優秀 | 800h+ |

**推薦**: 方案 A（最佳性價比）

---

## 成功案例參考

### Spring Data JPA

Spring Data JPA 在 Spring Boot 3 中通過以下機制實現自動配置：

1. **BeanFactoryInitializationAotProcessor**
   - 掃描所有 `@Entity` 類
   - 註冊構造器、字段、getter/setter

2. **@RegisterReflectionForBinding**
   - 聲明式註冊 DTO 類
   - 自動遞歸掃描嵌套類型

3. **RuntimeHintsRegistrar**
   - 註冊 Repository 代理
   - 註冊 JPA 元模型

**效果**: 用戶只需添加 `spring-boot-starter-data-jpa`，零手動配置

### MyBatis Native

MyBatis 也實現了類似機制：
- `MybatisRuntimeHints` 註冊 Mapper 介面
- 自動掃描 XML 文件
- 註冊 TypeHandler 反射

---

## 關鍵代碼示例

### AOT 處理器核心邏輯

```java
public class HiSqlRuntimeHints implements BeanFactoryInitializationAotProcessor {

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(
            ConfigurableListableBeanFactory beanFactory) {

        return (generationContext, beanFactoryInitializationCode) -> {
            RuntimeHints hints = generationContext.getRuntimeHints();

            // 1. 註冊所有 Repository 介面為代理
            String[] repos = beanFactory.getBeanNamesForType(SQLRepository.class);
            for (String repo : repos) {
                Class<?> repoClass = beanFactory.getType(repo);
                hints.proxies().registerJdkProxy(repoClass);
            }

            // 2. 掃描並註冊所有 @Entity 類
            scanAndRegisterEntities(hints);

            // 3. 註冊 XML 資源
            hints.resources().registerPattern("hisql/*.xml");

            // 4. 註冊策略類
            hints.reflection().registerType(
                MySQLPaginationMode.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS
            );
        };
    }

    private void scanAndRegisterEntities(RuntimeHints hints) {
        // 使用 Spring 的 ClassPath 掃描器
        PathMatchingResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver();

        Resource[] resources = resolver.getResources("classpath*:**/*.class");

        for (Resource resource : resources) {
            MetadataReader reader = metadataReaderFactory.getMetadataReader(resource);
            String className = reader.getClassMetadata().getClassName();
            Class<?> clazz = ClassUtils.forName(className, classLoader);

            if (clazz.isAnnotationPresent(Entity.class)) {
                // 註冊構造器、字段、方法
                hints.reflection().registerType(
                    clazz,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.DECLARED_FIELDS,
                    MemberCategory.INVOKE_DECLARED_METHODS
                );
            }
        }
    }
}
```

---

## 下一步行動

### 立即行動（本週）

1. ✅ **社區反饋**: 在 GitHub 創建 RFC Issue
2. ✅ **POC 項目**: 建立概念驗證分支
3. ✅ **測試基線**: 建立現有功能的完整測試

### 短期目標（1 個月內）

1. 完成 Spring Boot 3 升級
2. 實現基本的 AOT 處理器
3. 通過 Native Image 構建測試

### 長期目標（2-3 個月）

1. 完整的自動化掃描
2. 性能優化
3. 文檔與示例完善
4. 發布 v2.0.0

---

## 資源需求

### 人力

- **1 名高級開發**: 全職 2 個月
- **或 2 名中級開發**: 協作 1.5 個月

### 技能要求

- ✅ Spring Framework 深度理解
- ✅ GraalVM Native Image 經驗
- ✅ AOT 編譯原理
- ✅ 反射與代理機制

### 基礎設施

- CI/CD 支持 Native Image 構建
- 性能測試環境
- GraalVM 企業版許可證（可選）

---

## 常見問題

### Q1: 是否所有現有功能都支持？

**A**: 大部分支持，但以下場景可能受限：
- 過度動態的 SQL 構建（需編譯時可推斷）
- 運行時動態加載的類（需預先聲明）
- 自定義 ClassLoader（GraalVM 不支持）

### Q2: 性能是否有保證？

**A**: 構建時間會增加（+30-60 秒），但運行時性能顯著提升：
- 啟動時間: 60-100x 提升
- 內存占用: 5-10x 降低
- 穩定狀態性能: 與 JVM 相當

### Q3: 如何處理向下兼容？

**A**: 採用語義化版本控制：
- v1.x: 保持當前架構（Java 11, Spring Boot 2.x）
- v2.x: GraalVM 支持（Java 17+, Spring Boot 3.x）
- 提供詳細的遷移指南

### Q4: 是否需要用戶改變代碼？

**A**: 最小化改動：
- **無需改動**: 如果使用標準 `@Entity` + `@Repository`
- **可選改動**: 添加 `@HiSqlEntity` 以顯式聲明
- **必須改動**: 升級 Spring Boot 3 (javax → jakarta)

---

## 總結

### ✅ 可行性

**技術可行**: Spring Boot 3 提供完整的 AOT 基礎設施
**經濟可行**: 240 小時工作量，2 個月完成
**用戶可行**: 零配置體驗，與 Spring Data JPA 一致

### 🎯 核心價值

1. **開發者體驗**: 零手動配置，自動生成反射提示
2. **性能提升**: 60-100x 啟動速度，5-10x 內存節省
3. **雲原生**: 適合 Serverless、容器化部署
4. **生態系統**: 與 Spring Boot 3 深度集成

### 📊 投資回報

- **投入**: 2 個月開發時間
- **收益**:
  - 用戶獲得顯著性能提升
  - 項目技術棧現代化
  - 吸引更多貢獻者
  - 適應雲原生趨勢

### 🚀 推薦決策

**建議啟動該項目**，採用分階段實施策略，先發布 Alpha 版本徵求社區反饋。

---

**詳細技術方案**: 請參閱 [GRAALVM_MIGRATION_PLAN.md](./GRAALVM_MIGRATION_PLAN.md)

**更新日期**: 2025-11-15
