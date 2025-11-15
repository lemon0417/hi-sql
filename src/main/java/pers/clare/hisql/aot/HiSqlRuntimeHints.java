package pers.clare.hisql.aot;

import jakarta.persistence.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.aot.hint.*;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.util.ClassUtils;
import pers.clare.hisql.naming.LowerCaseNamingStrategy;
import pers.clare.hisql.naming.NamingStrategy;
import pers.clare.hisql.naming.UpperCaseNamingStrategy;
import pers.clare.hisql.page.H2PaginationMode;
import pers.clare.hisql.page.MSSQLPaginationMode;
import pers.clare.hisql.page.MySQLPaginationMode;
import pers.clare.hisql.page.PaginationMode;
import pers.clare.hisql.repository.SQLRepository;
import pers.clare.hisql.support.CommandTypeParser;
import pers.clare.hisql.support.ResultSetConverter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

/**
 * GraalVM Native Image AOT 處理器
 *
 * 自動掃描並註冊：
 * 1. 所有 Repository 介面 → 代理配置
 * 2. 所有 @Entity 類 → 反射配置
 * 3. 所有 XML 資源 → 資源配置
 * 4. 所有策略類 → 反射配置
 *
 * @author Claude
 * @since 2.0.0
 */
public class HiSqlRuntimeHints implements BeanFactoryInitializationAotProcessor, RuntimeHintsRegistrar {

    private static final Logger log = LogManager.getLogger(HiSqlRuntimeHints.class);

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(
            ConfigurableListableBeanFactory beanFactory) {

        return (generationContext, beanFactoryInitializationCode) -> {
            RuntimeHints hints = generationContext.getRuntimeHints();

            log.info("HiSQL AOT: Starting GraalVM Native Image hints registration");

            // 1. 註冊 Repository 代理
            registerRepositories(hints, beanFactory);

            // 2. 掃描並註冊 @Entity 類
            registerEntities(hints, beanFactory);

            // 3. 註冊 XML 資源
            registerResources(hints);

            // 4. 註冊策略類
            registerStrategies(hints);

            log.info("HiSQL AOT: Completed hints registration");
        };
    }

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // 作為 RuntimeHintsRegistrar 的實現
        // 主要由 processAheadOfTime 處理，這裡作為後備方案
        registerResources(hints);
        registerStrategies(hints);
    }

    /**
     * 註冊所有 SQLRepository 介面為 JDK 代理
     */
    private void registerRepositories(RuntimeHints hints, ConfigurableListableBeanFactory beanFactory) {
        try {
            String[] beanNames = beanFactory.getBeanNamesForType(SQLRepository.class, false, false);

            log.info("HiSQL AOT: Found {} repository interfaces", beanNames.length);

            for (String beanName : beanNames) {
                Class<?> repositoryClass = beanFactory.getType(beanName);
                if (repositoryClass != null && repositoryClass.isInterface()) {
                    // 註冊 JDK 動態代理
                    hints.proxies().registerJdkProxy(repositoryClass);

                    // 註冊介面方法反射
                    hints.reflection().registerType(
                        repositoryClass,
                        MemberCategory.INVOKE_DECLARED_METHODS,
                        MemberCategory.DECLARED_METHODS
                    );

                    log.debug("HiSQL AOT: Registered repository proxy: {}", repositoryClass.getName());
                }
            }
        } catch (Exception e) {
            log.warn("HiSQL AOT: Failed to register repositories", e);
        }
    }

    /**
     * 掃描並註冊所有 @Entity 類
     */
    private void registerEntities(RuntimeHints hints, ConfigurableListableBeanFactory beanFactory) {
        Set<Class<?>> entityClasses = new HashSet<>();

        try {
            // 掃描 classpath 尋找 @Entity 類
            PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(beanFactory.getBeanClassLoader());

            Resource[] resources = resolver.getResources("classpath*:**/*.class");
            SimpleMetadataReaderFactory metadataReaderFactory =
                new SimpleMetadataReaderFactory(beanFactory.getBeanClassLoader());

            log.info("HiSQL AOT: Scanning {} class files for @Entity annotations", resources.length);

            for (Resource resource : resources) {
                if (!resource.isReadable()) continue;

                try {
                    MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(resource);
                    String className = metadataReader.getClassMetadata().getClassName();

                    // 跳過明顯不是實體的包
                    if (shouldSkipClass(className)) {
                        continue;
                    }

                    Class<?> clazz = ClassUtils.forName(className, beanFactory.getBeanClassLoader());

                    // 如果是 @Entity 類
                    if (clazz.isAnnotationPresent(Entity.class)) {
                        entityClasses.add(clazz);
                        registerEntityClass(hints, clazz);
                    }
                } catch (Throwable e) {
                    // 忽略無法加載的類（可能是測試類或內部類）
                    log.trace("HiSQL AOT: Skipped class: {}", e.getMessage());
                }
            }

            log.info("HiSQL AOT: Registered {} entity classes", entityClasses.size());

        } catch (IOException e) {
            log.error("HiSQL AOT: Failed to scan entity classes", e);
        }
    }

    /**
     * 判斷是否應該跳過該類
     */
    private boolean shouldSkipClass(String className) {
        return className.startsWith("org.springframework.") ||
               className.startsWith("java.") ||
               className.startsWith("javax.") ||
               className.startsWith("jakarta.") ||
               className.startsWith("com.sun.") ||
               className.startsWith("sun.") ||
               className.startsWith("jdk.") ||
               className.contains("$") || // 跳過內部類
               className.startsWith("org.apache.") ||
               className.startsWith("org.junit.") ||
               className.startsWith("org.testng.");
    }

    /**
     * 註冊單個實體類的反射信息
     */
    private void registerEntityClass(RuntimeHints hints, Class<?> entityClass) {
        // 註冊構造器、字段、方法
        hints.reflection().registerType(
            entityClass,
            builder -> builder
                .withMembers(
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.DECLARED_FIELDS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.DECLARED_METHODS
                )
        );

        // 註冊所有字段為可訪問（包括 private）
        for (Field field : entityClass.getDeclaredFields()) {
            hints.reflection().registerField(field);
        }

        log.debug("HiSQL AOT: Registered entity: {}", entityClass.getName());
    }

    /**
     * 註冊 XML 資源文件
     */
    private void registerResources(RuntimeHints hints) {
        try {
            // 註冊 hisql/*.xml 模式
            hints.resources().registerPattern("hisql/*.xml");
            hints.resources().registerPattern("META-INF/spring.factories");
            hints.resources().registerPattern("META-INF/spring/aot.factories");

            // 掃描實際存在的 XML 文件
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] xmlResources = resolver.getResources("classpath*:hisql/*.xml");

            log.info("HiSQL AOT: Found {} XML resource files", xmlResources.length);

            for (Resource resource : xmlResources) {
                log.debug("HiSQL AOT: Registered XML resource: {}", resource.getFilename());
            }
        } catch (Exception e) {
            log.warn("HiSQL AOT: Failed to register resources", e);
        }
    }

    /**
     * 註冊內建策略類
     */
    private void registerStrategies(RuntimeHints hints) {
        // PaginationMode 實現
        registerStrategyClass(hints, MySQLPaginationMode.class);
        registerStrategyClass(hints, H2PaginationMode.class);
        registerStrategyClass(hints, MSSQLPaginationMode.class);
        registerStrategyClass(hints, PaginationMode.class);

        // NamingStrategy 實現
        registerStrategyClass(hints, LowerCaseNamingStrategy.class);
        registerStrategyClass(hints, UpperCaseNamingStrategy.class);
        registerStrategyClass(hints, NamingStrategy.class);

        // ResultSetConverter
        registerStrategyClass(hints, ResultSetConverter.class);

        // CommandTypeParser
        registerStrategyClass(hints, CommandTypeParser.class);

        log.info("HiSQL AOT: Registered built-in strategy classes");
    }

    /**
     * 註冊策略類的反射信息
     */
    private void registerStrategyClass(RuntimeHints hints, Class<?> strategyClass) {
        hints.reflection().registerType(
            strategyClass,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.DECLARED_FIELDS
        );

        log.debug("HiSQL AOT: Registered strategy: {}", strategyClass.getSimpleName());
    }
}
