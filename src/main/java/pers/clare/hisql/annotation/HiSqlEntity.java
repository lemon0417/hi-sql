package pers.clare.hisql.annotation;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;

import java.lang.annotation.*;

/**
 * 標記實體類以支持 GraalVM Native Image
 *
 * 使用此註解可以顯式聲明實體類，確保在 Native Image 編譯時正確註冊反射信息。
 *
 * <p>用法：
 * <pre>{@code
 * @HiSqlEntity
 * @Entity
 * public class User {
 *     @Id
 *     private Long id;
 *     private String name;
 * }
 * }</pre>
 *
 * <p>注意：如果已經使用 {@code @Entity} 註解，通常不需要額外添加此註解，
 * 因為 HiSQL 的 AOT 處理器會自動掃描所有 {@code @Entity} 類。
 * 此註解主要用於：
 * <ul>
 *     <li>顯式聲明某個類需要反射支持</li>
 *     <li>非 @Entity 的 DTO 類</li>
 *     <li>作為文檔化標記</li>
 * </ul>
 *
 * @author Claude
 * @since 2.0.0
 * @see RegisterReflectionForBinding
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@RegisterReflectionForBinding
public @interface HiSqlEntity {

    /**
     * 是否註冊所有字段的 getter/setter
     *
     * @return true 表示註冊所有 getter/setter，false 只註冊構造器和字段
     */
    boolean includeAccessors() default true;

    /**
     * 是否註冊所有構造器
     *
     * @return true 表示註冊所有構造器，false 只註冊無參構造器
     */
    boolean includeAllConstructors() default true;
}
