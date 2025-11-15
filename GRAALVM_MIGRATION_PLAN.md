# GraalVM Native Image 遷移計劃

## 執行摘要

本文檔評估將 **hi-sql** 專案改造為支援 GraalVM Native Image，並實現類似 Spring Boot JPA 的自動反射配置，無需手動維護 `reflect-config.json`。

**結論**: 可行，但需要進行架構調整。建議採用 Spring Boot 3.x + AOT 處理器的方案。

---

## 目錄

1. [當前架構分析](#當前架構分析)
2. [GraalVM 兼容性問題](#graalvm-兼容性問題)
3. [Spring Boot 3 AOT 處理方案](#spring-boot-3-aot-處理方案)
4. [實施策略](#實施策略)
5. [具體實作步驟](#具體實作步驟)
6. [工作量評估](#工作量評估)
7. [風險與挑戰](#風險與挑戰)

---

## 當前架構分析

### 反射使用情況

Hi-SQL 大量使用反射機制，主要分為以下幾類：

#### 1. **實體類反射** (Entity Reflection)

**位置**: `ClassUtil.java`, `SQLStoreFactory.java`, `FieldColumnFactory.java`

```java
// ClassUtil.java:28-34 - 獲取所有字段並設置可訪問
Field[] fields = c.getDeclaredFields();
for (Field field : fields) {
    field.setAccessible(true);
}
```

**用途**:
- 掃描實體類的所有字段
- 讀取 JPA 註解 (`@Id`, `@Column`, `@GeneratedValue`, `@Table`)
- 建立字段與資料庫列的映射關係

#### 2. **方法發現與調用** (Method Discovery & Invocation)

**位置**: `SQLMethodFactory.java`, `SQLMethodInterceptor.java`, `ArgumentParseUtil.java`

```java
// SQLMethodFactory.java:71 - 掃描 Repository 介面的所有方法
Method[] methods = clazz.getDeclaredMethods();

// ArgumentParseUtil.java:88 - 調用 getter 方法提取參數
method.invoke(obj)
```

**用途**:
- 分析 Repository 介面方法簽名
- 提取方法參數（包括 Pagination, Sort, Callback）
- 動態調用對象的 getter 方法獲取參數值

#### 3. **動態代理** (Dynamic Proxy)

**位置**: `SQLProxyFactory.java`, `SQLRepositoryFactoryBean.java`

```java
// SQLRepositoryFactoryBean.java:54 - 創建 JDK 動態代理
proxyFactory.getProxy(classLoader)
```

**用途**:
- 為 `@Repository` 介面創建運行時實現
- 攔截方法調用並執行 SQL

#### 4. **構造器反射** (Constructor Reflection)

**位置**: `SQLStoreFactory.java:108`, `ResultSetUtil.java:129`

```java
// 創建實體實例
Constructor<T> constructor = clazz.getConstructor();
constructor.newInstance();
```

**用途**:
- 從 ResultSet 映射為實體對象時創建實例

#### 5. **策略類實例化** (Strategy Instantiation)

**位置**: `SQLScanRegistrar.java:101-107`

```java
// 動態實例化配置的策略類
paginationModeClass.getConstructor().newInstance();
namingClass.getConstructor().newInstance();
resultSetConverter.getConstructor().newInstance();
```

**用途**:
- 實例化用戶配置的 `PaginationMode`, `NamingStrategy`, `ResultSetConverter`

#### 6. **XML 資源加載** (Resource Loading)

**位置**: `SQLInjector.java:36`

```java
// 加載 SQL XML 文件
getClassLoader().getResourceAsStream(xmlPath)
```

**用途**:
- 從 `resources/hisql/` 加載外部 SQL 定義

---

### 架構優勢（有利於 GraalVM 遷移）

✅ **無 CGLIB 依賴**: 使用 Spring AOP 的 JDK 動態代理
✅ **無 `sun.misc.Unsafe`**: 沒有使用底層 API
✅ **緩存機制完善**: 大部分反射結果都有緩存（`ConcurrentHashMap`）
✅ **明確的掃描範圍**: 通過 `@EnableHiSql(basePackages)` 明確指定掃描包

---

## GraalVM 兼容性問題

### 問題 1: 運行時反射無法追蹤

**問題描述**:
GraalVM 無法在編譯時靜態分析以下代碼：

```java
// ClassUtil.java - 動態獲取字段
Field[] fields = entityClass.getDeclaredFields();
```

**影響**:
- 所有實體類（User, Product 等）的字段在 Native Image 中不可訪問
- ResultSet 無法映射為實體對象

**解決方案**: 需要在編譯時註冊所有實體類的反射信息

---

### 問題 2: 動態代理介面未註冊

**問題描述**:
```java
// SQLRepositoryFactoryBean.java - 動態創建代理
Object proxy = proxyFactory.getProxy(classLoader);
```

**影響**:
- Repository 介面（UserRepository, ProductRepository）無法創建代理實例
- 應用無法啟動

**解決方案**: 需要註冊所有 Repository 介面為代理類型

---

### 問題 3: XML 資源文件未包含

**問題描述**:
```java
// SQLInjector.java - 加載 classpath 資源
InputStream is = classLoader.getResourceAsStream("hisql/CustomRepository.xml");
```

**影響**:
- XML 中定義的 SQL 無法加載
- 依賴 XML 的方法調用失敗

**解決方案**: 需要註冊 resource-config.json 或使用 AOT 處理

---

### 問題 4: 策略類實例化失敗

**問題描述**:
```java
// SQLScanRegistrar.java - 反射實例化策略
Class<? extends PaginationMode> paginationModeClass = ...;
paginationModeClass.getConstructor().newInstance();
```

**影響**:
- 自定義的 `PaginationMode`, `NamingStrategy` 無法實例化
- 配置失效

**解決方案**: 需要註冊策略類的無參構造器

---

## Spring Boot 3 AOT 處理方案

### Spring Boot JPA 的做法

Spring Data JPA 在 Spring Boot 3 中通過以下機制實現自動配置：

#### 1. **AOT 處理器** (Ahead-of-Time Processor)

Spring 在構建時執行 AOT 處理器，自動生成以下內容：

```
target/spring-aot/main/sources/
├── org/springframework/aot/
│   └── StaticSpringFactories.java
├── pers/clare/hisql/
│   └── _HiSqlRuntimeHints.java
└── META-INF/native-image/
    ├── reflect-config.json       # 自動生成
    ├── proxy-config.json          # 自動生成
    └── resource-config.json       # 自動生成
```

#### 2. **RuntimeHintsRegistrar 介面**

Spring Boot 3 提供了 `RuntimeHintsRegistrar` 介面，用於程式化註冊反射提示：

```java
public class HiSqlRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // 註冊實體類反射
        hints.reflection()
            .registerType(User.class, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                                      MemberCategory.DECLARED_FIELDS);

        // 註冊代理介面
        hints.proxies().registerJdkProxy(UserRepository.class);

        // 註冊資源文件
        hints.resources().registerPattern("hisql/*.xml");
    }
}
```

#### 3. **@RegisterReflectionForBinding 註解**

用於聲明式註冊資料綁定類：

```java
@Configuration
@RegisterReflectionForBinding({User.class, Product.class})
public class HiSqlConfig {
}
```

Spring 會自動註冊這些類的：
- 構造器
- 所有字段
- Getter/Setter 方法
- Record components（如果是 Java 14+ record）

#### 4. **BeanFactoryInitializationAotProcessor**

用於處理 Bean 定義的 AOT 優化：

```java
public class HiSqlRepositoryAotProcessor implements BeanFactoryInitializationAotProcessor {

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(
            ConfigurableListableBeanFactory beanFactory) {

        // 在這裡掃描所有 Repository 並註冊提示
        return (generationContext, beanFactoryInitializationCode) -> {
            // 生成初始化代碼
        };
    }
}
```

---

## 實施策略

### 策略 A: 升級到 Spring Boot 3 + 自定義 AOT 處理器（推薦）

**優點**:
- ✅ 完全自動化，用戶無需手動配置
- ✅ 與 Spring 生態系統深度集成
- ✅ 利用 Spring 的 AOT 基礎設施
- ✅ 類似 Spring Data JPA 的體驗

**缺點**:
- ❌ 需要升級到 Spring Boot 3.x（破壞性變更）
- ❌ 需要 Java 17+
- ❌ 需要大量測試確保兼容性

**適用場景**: 長期維護的項目，願意投入升級成本

---

### 策略 B: 保持 Spring Boot 2.x + GraalVM Reachability Metadata

**優點**:
- ✅ 無需升級 Spring Boot
- ✅ 保持 Java 11 兼容性
- ✅ 改動較小

**缺點**:
- ❌ 需要手動維護 `reflect-config.json`
- ❌ 用戶需要自己配置實體類
- ❌ 體驗不如 Spring Boot 3

**適用場景**: 短期內無法升級的項目

---

### 策略 C: 編譯時代碼生成（最激進）

**優點**:
- ✅ 完全消除運行時反射
- ✅ 最佳性能
- ✅ 可支持 Java 11

**缺點**:
- ❌ 需要重寫大部分核心代碼
- ❌ 需要自定義 Annotation Processor
- ❌ 工作量巨大（預估 3-6 個月）

**適用場景**: 追求極致性能的項目

---

## 具體實作步驟

### 階段 1: 升級依賴（策略 A）

#### 1.1 升級 Spring Boot 到 3.x

```xml
<!-- pom.xml -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.1</version>
</parent>

<properties>
    <java.version>17</java.version>
</properties>
```

#### 1.2 替換 javax.persistence 為 jakarta.persistence

```xml
<dependency>
    <groupId>jakarta.persistence</groupId>
    <artifactId>jakarta.persistence-api</artifactId>
</dependency>
```

全局替換：
```bash
find . -name "*.java" -exec sed -i 's/javax.persistence/jakarta.persistence/g' {} +
```

#### 1.3 添加 GraalVM 依賴

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aot</artifactId>
</dependency>

<build>
    <plugins>
        <plugin>
            <groupId>org.graalvm.buildtools</groupId>
            <artifactId>native-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

---

### 階段 2: 實現 AOT 處理器

#### 2.1 創建 RuntimeHintsRegistrar

**文件**: `src/main/java/pers/clare/hisql/aot/HiSqlRuntimeHints.java`

```java
package pers.clare.hisql.aot;

import org.springframework.aot.hint.*;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.Resource;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.util.ClassUtils;
import pers.clare.hisql.repository.SQLRepository;
import pers.clare.hisql.naming.NamingStrategy;
import pers.clare.hisql.page.PaginationMode;
import pers.clare.hisql.support.ResultSetConverter;
import pers.clare.hisql.util.FieldColumnFactory;

import jakarta.persistence.Entity;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

public class HiSqlRuntimeHints implements BeanFactoryInitializationAotProcessor {

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(
            ConfigurableListableBeanFactory beanFactory) {

        return (generationContext, beanFactoryInitializationCode) -> {
            RuntimeHints hints = generationContext.getRuntimeHints();

            // 1. 掃描所有 Repository 介面
            registerRepositories(hints, beanFactory);

            // 2. 掃描所有 @Entity 類
            registerEntities(hints, beanFactory);

            // 3. 註冊策略類
            registerStrategies(hints);

            // 4. 註冊 XML 資源
            registerResources(hints);
        };
    }

    private void registerRepositories(RuntimeHints hints, ConfigurableListableBeanFactory beanFactory) {
        String[] beanNames = beanFactory.getBeanNamesForType(SQLRepository.class);

        for (String beanName : beanNames) {
            Class<?> repositoryClass = beanFactory.getType(beanName);
            if (repositoryClass != null) {
                // 註冊代理
                hints.proxies().registerJdkProxy(repositoryClass);

                // 註冊介面方法
                hints.reflection().registerType(
                    repositoryClass,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.DECLARED_METHODS
                );
            }
        }
    }

    private void registerEntities(RuntimeHints hints, ConfigurableListableBeanFactory beanFactory) {
        try {
            // 掃描 classpath 尋找 @Entity 類
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:**/*.class");
            SimpleMetadataReaderFactory metadataReaderFactory = new SimpleMetadataReaderFactory();

            for (Resource resource : resources) {
                if (!resource.isReadable()) continue;

                try {
                    MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(resource);
                    String className = metadataReader.getClassMetadata().getClassName();

                    Class<?> clazz = ClassUtils.forName(className, beanFactory.getBeanClassLoader());

                    // 如果是 @Entity 類
                    if (clazz.isAnnotationPresent(Entity.class)) {
                        registerEntityClass(hints, clazz);
                    }
                } catch (Exception e) {
                    // 忽略無法加載的類
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan entity classes", e);
        }
    }

    private void registerEntityClass(RuntimeHints hints, Class<?> entityClass) {
        // 註冊構造器、字段、方法
        hints.reflection().registerType(
            entityClass,
            builder -> builder
                .withMembers(
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.DECLARED_FIELDS,
                    MemberCategory.INVOKE_DECLARED_METHODS
                )
        );

        // 註冊所有字段為可訪問
        for (Field field : entityClass.getDeclaredFields()) {
            hints.reflection().registerField(field);
        }
    }

    private void registerStrategies(RuntimeHints hints) {
        // 註冊內建策略類
        registerStrategyClass(hints, "pers.clare.hisql.naming.LowerCaseNamingStrategy");
        registerStrategyClass(hints, "pers.clare.hisql.naming.UpperCaseNamingStrategy");
        registerStrategyClass(hints, "pers.clare.hisql.page.MySQLPaginationMode");
        registerStrategyClass(hints, "pers.clare.hisql.page.H2PaginationMode");
        registerStrategyClass(hints, "pers.clare.hisql.page.MSSQLPaginationMode");
        registerStrategyClass(hints, "pers.clare.hisql.support.ResultSetConverter");
    }

    private void registerStrategyClass(RuntimeHints hints, String className) {
        try {
            Class<?> clazz = Class.forName(className);
            hints.reflection().registerType(
                clazz,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS
            );
        } catch (ClassNotFoundException e) {
            // 忽略不存在的類
        }
    }

    private void registerResources(RuntimeHints hints) {
        // 註冊 XML 資源文件模式
        hints.resources().registerPattern("hisql/*.xml");
        hints.resources().registerPattern("META-INF/spring.factories");
        hints.resources().registerPattern("META-INF/spring/aot.factories");
    }
}
```

#### 2.2 註冊 AOT 處理器

**文件**: `src/main/resources/META-INF/spring/aot.factories`

```properties
org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor=\
pers.clare.hisql.aot.HiSqlRuntimeHints
```

---

### 階段 3: 提供用戶級別的便利註解

#### 3.1 創建 @HiSqlEntity 註解

**文件**: `src/main/java/pers/clare/hisql/annotation/HiSqlEntity.java`

```java
package pers.clare.hisql.annotation;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import java.lang.annotation.*;

/**
 * 標記實體類以支持 GraalVM Native Image
 *
 * 用法:
 * <pre>
 * @HiSqlEntity
 * public class User {
 *     private Long id;
 *     private String name;
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@RegisterReflectionForBinding
public @interface HiSqlEntity {
}
```

#### 3.2 擴展 @EnableHiSql 註解

**文件**: `src/main/java/pers/clare/hisql/annotation/EnableHiSql.java`

```java
package pers.clare.hisql.annotation;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Import;
import pers.clare.hisql.repository.SQLScanRegistrar;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Import(SQLScanRegistrar.class)
public @interface EnableHiSql {

    String[] value() default {};

    String[] basePackages() default {};

    Class<?>[] basePackageClasses() default {};

    /**
     * 實體類列表，用於 GraalVM Native Image 支持
     *
     * 示例:
     * @EnableHiSql(entities = {User.class, Product.class})
     */
    @RegisterReflectionForBinding
    Class<?>[] entities() default {};

    // ... 其他現有屬性
}
```

---

### 階段 4: 改進掃描機制

#### 4.1 使用編譯時已知的類列表

修改 `SQLRepositoryScanner.java` 以支持 AOT 友好的掃描：

```java
package pers.clare.hisql.repository;

import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.util.Set;

public class SQLRepositoryScanner extends ClassPathBeanDefinitionScanner
        implements RuntimeHintsRegistrar {

    @Override
    protected Set<BeanDefinitionHolder> doScan(String... basePackages) {
        Set<BeanDefinitionHolder> beanDefinitions = super.doScan(basePackages);

        // 在 AOT 階段，註冊發現的所有 Repository
        if (isAotProcessing()) {
            for (BeanDefinitionHolder holder : beanDefinitions) {
                registerRepositoryHints(holder.getBeanDefinition().getBeanClassName());
            }
        }

        return beanDefinitions;
    }

    private boolean isAotProcessing() {
        // 檢測是否在 AOT 處理階段
        return Boolean.getBoolean("spring.aot.processing");
    }

    private void registerRepositoryHints(String className) {
        // 在 AOT 階段註冊提示
        // 實際註冊邏輯由 HiSqlRuntimeHints 處理
    }

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // 註冊掃描器自身需要的提示
        hints.reflection().registerType(SQLRepository.class);
    }
}
```

---

### 階段 5: XML 資源處理優化

#### 5.1 在 AOT 階段預加載 XML

修改 `SQLInjector.java`:

```java
package pers.clare.hisql.method;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;

public class SQLInjector implements RuntimeHintsRegistrar {

    // 現有代碼...

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        try {
            // 在 AOT 階段掃描所有 XML 文件
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
            Resource[] resources = resolver.getResources("classpath*:hisql/*.xml");

            for (Resource resource : resources) {
                // 註冊每個 XML 文件
                String path = resource.getURI().toString();
                if (path.contains("hisql/")) {
                    String relativePath = path.substring(path.indexOf("hisql/"));
                    hints.resources().registerPattern(relativePath);
                }
            }
        } catch (IOException e) {
            // 記錄警告但不中斷 AOT 處理
            System.err.println("Warning: Failed to scan SQL XML files: " + e.getMessage());
        }
    }
}
```

註冊到 `META-INF/spring/aot.factories`:
```properties
org.springframework.aot.hint.RuntimeHintsRegistrar=\
pers.clare.hisql.method.SQLInjector,\
pers.clare.hisql.repository.SQLRepositoryScanner
```

---

## 階段 6: 測試與驗證

### 6.1 添加測試配置

**文件**: `src/test/java/pers/clare/hisql/NativeImageTest.java`

```java
package pers.clare.hisql;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.test.context.SpringBootTest;
import pers.clare.hisql.data.entity.User;
import pers.clare.hisql.data.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class NativeImageTest {

    @Test
    void shouldRegisterEntityReflectionHints() {
        RuntimeHints hints = new RuntimeHints();
        new HiSqlRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection()
            .onType(User.class))
            .accepts(hints);
    }

    @Test
    void shouldRegisterRepositoryProxyHints() {
        RuntimeHints hints = new RuntimeHints();
        new HiSqlRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.proxies()
            .forInterfaces(UserRepository.class))
            .accepts(hints);
    }

    @Test
    void shouldRegisterXmlResourceHints() {
        RuntimeHints hints = new RuntimeHints();
        new HiSqlRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.resource()
            .forResource("hisql/CustomRepository.xml"))
            .accepts(hints);
    }
}
```

### 6.2 構建 Native Image

```bash
# 1. 生成 AOT 優化代碼
mvn spring-boot:process-aot

# 2. 檢查生成的提示文件
ls -la target/spring-aot/main/resources/META-INF/native-image/

# 3. 構建 Native Image
mvn -Pnative native:compile

# 4. 運行 Native Image
./target/hi-sql-native

# 5. 測試
./target/hi-sql-native --spring.profiles.active=test
```

---

## 工作量評估

### 時間估算（基於策略 A）

| 階段 | 任務 | 預估工時 | 難度 |
|------|------|----------|------|
| 1 | 升級 Spring Boot 3.x | 40 小時 | 高 |
| 1.1 | 依賴升級與兼容性修復 | 16 小時 | 中 |
| 1.2 | javax → jakarta 遷移 | 8 小時 | 低 |
| 1.3 | 編譯錯誤修復 | 16 小時 | 中 |
| 2 | 實現 AOT 處理器 | 60 小時 | 高 |
| 2.1 | HiSqlRuntimeHints 實現 | 24 小時 | 高 |
| 2.2 | Repository 掃描邏輯 | 16 小時 | 中 |
| 2.3 | Entity 掃描邏輯 | 16 小時 | 中 |
| 2.4 | 策略類註冊 | 4 小時 | 低 |
| 3 | 用戶便利性功能 | 24 小時 | 中 |
| 3.1 | @HiSqlEntity 註解 | 8 小時 | 低 |
| 3.2 | @EnableHiSql 擴展 | 8 小時 | 低 |
| 3.3 | 文檔與範例 | 8 小時 | 低 |
| 4 | 掃描機制優化 | 32 小時 | 高 |
| 4.1 | SQLRepositoryScanner 改造 | 16 小時 | 中 |
| 4.2 | AOT 友好的類加載 | 16 小時 | 高 |
| 5 | XML 資源處理 | 16 小時 | 中 |
| 5.1 | SQLInjector AOT 支持 | 12 小時 | 中 |
| 5.2 | 資源註冊自動化 | 4 小時 | 低 |
| 6 | 測試與驗證 | 48 小時 | 中 |
| 6.1 | 單元測試 | 16 小時 | 中 |
| 6.2 | Native Image 構建測試 | 16 小時 | 中 |
| 6.3 | 性能測試與優化 | 16 小時 | 中 |
| 7 | 文檔與發布 | 20 小時 | 低 |
| **總計** | | **240 小時** | |

**約 6 週（1.5 個月）** - 單人全職工作

---

## 風險與挑戰

### 技術風險

#### 1. **Spring Boot 3 升級兼容性**

**風險**: 破壞性變更可能導致現有功能失效

**緩解措施**:
- 建立完整的測試套件
- 在分支上進行升級，保持主分支穩定
- 逐步遷移，而非一次性升級

#### 2. **AOT 處理器的複雜性**

**風險**: 無法正確掃描所有需要反射的類

**緩解措施**:
- 提供手動註冊機制作為後備方案
- 使用 GraalVM Tracing Agent 驗證遺漏的配置
- 提供詳細的錯誤信息和調試日誌

#### 3. **動態 SQL 的限制**

**風險**: GraalVM 可能無法處理過於動態的 SQL 構建

**緩解措施**:
- 文檔化不支持的模式
- 在編譯時驗證 SQL 模板
- 提供靜態 SQL 替代方案

#### 4. **性能回歸**

**風險**: AOT 處理可能增加啟動時間或內存使用

**緩解措施**:
- 建立性能基準測試
- 優化掃描邏輯（緩存、懶加載）
- 提供配置選項控制掃描範圍

---

### 維護風險

#### 1. **雙模式維護**

**風險**: 需要同時支持 JVM 和 Native Image 模式

**緩解措施**:
- 使用 CI/CD 自動化測試兩種模式
- 統一代碼路徑，避免條件編譯

#### 2. **用戶學習曲線**

**風險**: 用戶需要學習新的配置方式

**緩解措施**:
- 提供遷移指南
- 向下兼容舊配置（如果可能）
- 豐富的示例和文檔

---

## 推薦方案

### 首選: 策略 A（Spring Boot 3 + AOT）

**理由**:
1. **長期可維護性**: 與 Spring 生態系統保持一致
2. **用戶體驗**: 類似 Spring Data JPA，無需手動配置
3. **社區支持**: 利用 Spring 的成熟工具鏈
4. **未來證明**: Spring Boot 3 是未來方向

### 實施路線圖

#### 第 1 階段: 準備工作（2 週）
- 建立完整的測試套件
- 創建升級分支
- 升級 Spring Boot 到 3.2.x
- 修復編譯錯誤

#### 第 2 階段: 核心實現（3 週）
- 實現 `HiSqlRuntimeHints`
- 改造 `SQLRepositoryScanner`
- 實現 Entity 自動掃描
- XML 資源處理

#### 第 3 階段: 測試與優化（1 週）
- Native Image 構建測試
- 性能基準測試
- 修復 Bug

#### 第 4 階段: 文檔與發布（1 週）
- 遷移指南
- 範例項目
- 發布 2.0.0（破壞性版本）

---

## 示例：用戶使用體驗

### 當前方式（手動配置）

```java
// 需要手動維護 reflect-config.json
{
  "name": "com.example.User",
  "allDeclaredFields": true,
  "allDeclaredConstructors": true
}
```

### 改造後（自動配置）

```java
// 選項 1: 使用 @HiSqlEntity
@HiSqlEntity
@Entity
public class User {
    @Id
    private Long id;
    private String name;
}

// 選項 2: 在配置類中聲明
@Configuration
@EnableHiSql(entities = {User.class, Product.class})
public class HiSqlConfig {
}

// 選項 3: 完全自動掃描（推薦）
@Configuration
@EnableHiSql(basePackages = "com.example")
public class HiSqlConfig {
}
// 自動掃描所有 @Entity 和 Repository 介面
```

**構建 Native Image**:
```bash
mvn -Pnative native:compile
# 所有反射配置自動生成！
```

---

## 附錄

### A. 參考資料

1. [Spring Boot 3 Native Image 官方文檔](https://docs.spring.io/spring-boot/docs/3.2.3/reference/html/native-image.html)
2. [Spring AOT 處理](https://docs.spring.io/spring-framework/reference/core/aot.html)
3. [GraalVM Native Image 指南](https://www.graalvm.org/latest/reference-manual/native-image/)
4. [Spring Data JPA Native Image 支持](https://github.com/spring-projects/spring-data-jpa/tree/main/spring-data-jpa/src/main/java/org/springframework/data/jpa/aot)

### B. 相關 Issue 追蹤

建議在 GitHub 創建以下 Issues：

1. `[RFC] GraalVM Native Image Support` - 徵求社區反饋
2. `Upgrade to Spring Boot 3.x` - 追蹤升級進度
3. `Implement AOT RuntimeHintsRegistrar` - 核心實現
4. `Add Native Image integration tests` - 測試覆蓋

---

## 總結

將 hi-sql 改造為支援 GraalVM Native Image 並實現自動反射配置是**可行的**，推薦採用 **Spring Boot 3 + AOT 處理器**的方案。

**關鍵成功因素**:
1. 完整的測試覆蓋
2. 分階段實施
3. 向下兼容考慮
4. 詳細的文檔

**預估投入**: 約 6 週開發時間（單人），建議分配 2 個月（包含測試和文檔）

**用戶收益**:
- ✅ 零配置的 Native Image 支持
- ✅ 類似 Spring Data JPA 的開發體驗
- ✅ 顯著的啟動速度提升（~10x）
- ✅ 更低的內存占用（~1/5）

---

**建議下一步**:
1. 在社區中徵求反饋（創建 RFC Issue）
2. 建立概念驗證（POC）項目
3. 制定詳細的遷移計劃
4. 開始第 1 階段實施
