# CLAUDE.md - Hi-SQL Codebase Guide for AI Assistants

## Project Overview

**Hi-SQL** is a lightweight, pure SQL library for Java that provides an alternative to traditional ORM frameworks. It emphasizes native SQL while offering Spring Framework integration, parameterized queries, result mapping, and transaction support.

**Key Information:**
- **Language**: Java 11+
- **Framework**: Spring Framework 5+, Spring Boot 2.5.6
- **Build Tool**: Maven
- **Version**: 1.3.6.1-RELEASE
- **License**: Apache 2.0
- **Repository**: https://github.com/babyblue94520/hi-sql

## Philosophy

Hi-SQL takes a "SQL-first" approach, avoiding the complexities of ORM frameworks. Developers write native SQL directly, with the framework handling:
- Parameterization and dynamic SQL substitution
- Result mapping to Java objects
- Connection and transaction management
- Pagination with database-specific optimizations

## Architecture Overview

### Directory Structure

```
hi-sql/
├── src/main/java/pers/clare/hisql/
│   ├── annotation/          # @EnableHiSql, @HiSql annotations
│   ├── constant/            # Enums and constants (CommandType)
│   ├── exception/           # HiSqlException
│   ├── function/            # Functional interfaces (callbacks, handlers)
│   ├── method/              # Method interception and proxy creation
│   ├── naming/              # Naming strategies (camelCase ↔ snake_case)
│   ├── page/                # Pagination support (Page, Pagination, Sort)
│   ├── query/               # SQL query building
│   ├── repository/          # Core repository interfaces and implementations
│   ├── service/             # Service layer hierarchy
│   ├── store/               # Entity metadata and SQL stores
│   ├── support/             # SQL replacement and ResultSet conversion
│   └── util/                # Reflection, JDBC, and utility classes
├── src/test/java/pers/clare/hisql/
│   ├── data/                # Test entities, repositories, config
│   ├── performance/         # Performance benchmarks
│   ├── repository/          # Repository tests
│   └── service/             # Service tests
├── src/test/resources/
│   ├── application*.yml     # Test database configurations
│   └── hisql/               # XML SQL definitions
└── pom.xml
```

### Core Components

#### 1. Annotations (`/annotation`)

**@EnableHiSql** - Primary configuration annotation
```java
@EnableHiSql(
    basePackages = {"com.example"},           // Packages to scan
    paginationMode = MySQLPaginationMode.class, // Database-specific pagination
    naming = LowerCaseNamingStrategy.class,   // camelCase to snake_case
    resultSetConverter = CustomResultSetConverter.class,
    xmlRootPath = "hisql"                     // XML root directory
)
```

**@HiSql** - Method-level SQL definition
```java
@HiSql("select * from user where id = :id")
User findById(Long id);

@HiSql(name = "findByAccount", returnIncrementKey = true)
Long insert(User user);
```

#### 2. Repository Interfaces (`/repository`)

**SQLRepository** - Base interface
- Low-level JDBC operations
- Connection callbacks
- Generic query execution
- Located: `/home/user/hi-sql/src/main/java/pers/clare/hisql/repository/SQLRepository.java`

**SQLCrudRepository<T, K>** - CRUD operations
- `count()`, `findAll()`, `findById(K)`
- `insert(T)`, `insertAll(Collection<T>)`
- `update(T)`, `updateAll(Collection<T>)`
- `delete(T)`, `deleteById(K)`, `deleteByIds(K[])`
- `findAllByIds(K[])`, `deleteByIds(K[])`
- `page(Pagination)`, `next(Pagination)`
- Located: `/home/user/hi-sql/src/main/java/pers/clare/hisql/repository/SQLCrudRepository.java`

#### 3. Service Layer Hierarchy (`/service`)

```
SQLBasicService (find, findAll, findSet, findMap)
    └── SQLPageService (page, pageMap, next, nextMap)
            └── SQLQueryService (query building)
                    └── SQLService (insert, update)
                            └── SQLStoreService (store management)
```

Key service classes:
- **SQLService**: `/home/user/hi-sql/src/main/java/pers/clare/hisql/service/SQLService.java`
- **SQLStoreService**: `/home/user/hi-sql/src/main/java/pers/clare/hisql/service/SQLStoreService.java`

#### 4. Method Interception (`/method`)

**SQLMethodFactory** - Creates method interceptors
- Analyzes return types (List, Set, Page, Next, Map, single objects)
- Builds SQL invokers
- Handles pagination, sorting, callbacks
- Located: `/home/user/hi-sql/src/main/java/pers/clare/hisql/method/SQLMethodFactory.java` (~428 lines)

**SQLProxyFactory** - Creates dynamic proxies
- Located: `/home/user/hi-sql/src/main/java/pers/clare/hisql/method/SQLProxyFactory.java`

**SQLInjector** - Loads SQL from XML files
- Located: `/home/user/hi-sql/src/main/java/pers/clare/hisql/method/SQLInjector.java`

#### 5. Store System (`/store`)

**SQLStore** - Generic entity metadata store
- Constructor caching
- Field setter mappings
- ResultSet to object conversion

**SQLCrudStore** - CRUD-specific metadata
- Table name, primary keys, auto-increment keys
- Pre-built SQL queries (count, select, insert, update, delete)
- Lazy-initialized QueryBuilders for ID-based operations
- Located: `/home/user/hi-sql/src/main/java/pers/clare/hisql/store/SQLCrudStore.java` (~108 lines)

#### 6. Pagination (`/page`)

**PaginationMode** - Database-specific implementations
- MySQL: `LIMIT offset, size`
- H2: `LIMIT size OFFSET offset`
- MSSQL: `OFFSET offset ROWS FETCH NEXT size ROWS ONLY`

**Pagination** - Pagination parameters
- Properties: `page`, `size`, `sorts`, `total`
- Supports virtual totals (estimated counts)

#### 7. Naming Strategies (`/naming`)

**NamingStrategy** - Case conversion interface
- **LowerCaseNamingStrategy**: `userName` → `user_name`
- **UpperCaseNamingStrategy**: `userName` → `USER_NAME`

## Key Concepts

### 1. SQL Parameterization

**Named Parameters** - `:paramName`
```java
@HiSql("select * from user where id = :id")
User findById(Long id);
```

**Object Parameters** - `:obj.fieldName`
```java
@HiSql("select * from user where account = :user.account")
User findByAccount(User user);
```

**Collection Parameters** - `:ids`
```java
@HiSql("select * from user where id in :ids")
List<User> findByIds(Long[] ids);
```

**Multi-Column IN Queries**
```java
@HiSql("select * from user where (id, account) in :values")
List<User> findByComposite(Object[][] values);
```

### 2. Dynamic SQL Substitution

Use `{placeholder}` for conditional SQL fragments:

```java
@HiSql("select * from user where 1=1 {condition}")
List<User> findAll(String condition, Object... params);

// Usage
repository.findAll("and enabled = :enabled", true);
```

**With SqlReplace** - Conditional replacement:
```java
@HiSql("select * from user where 1=1 {filter}")
List<User> findFiltered(SqlReplace<Object> filter);

// Usage - replaces {filter} with SQL only if value is not null
repository.findFiltered(SqlReplace.of(value, "and enabled = :enabled"));
```

**Important**: Always use placeholders `{...}` for dynamic SQL to prevent SQL injection. Never concatenate SQL strings externally.

### 3. XML-based SQL Definitions

**Location**: `src/test/resources/hisql/RepositoryName.xml`

**Format**:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE SQL>
<SQL>
    <methodName><![CDATA[
        select * from user where id = :id
    ]]></methodName>
</SQL>
```

**Usage**:
```java
@Repository
public interface UserRepository extends SQLRepository {
    @HiSql(name = "methodName")
    User findById(Long id);
}
```

### 4. Return Type Handling

The framework automatically determines query execution strategy based on return type:

- **Single object**: `User`, `Long`, `String` → Returns first result or null
- **List**: `List<User>` → Returns all results
- **Set**: `Set<User>` → Returns unique results
- **Map**: `Map<Long, User>` → Returns keyed results
- **Page**: `Page<User>` → Returns paginated results with total count
- **Next**: `Next<User>` → Returns paginated results without total count
- **Array**: `User[]` → Returns array of results
- **void**: Execute update/insert without returning data

### 5. Pagination Optimization

**Standard Pagination**:
```java
Pagination pagination = Pagination.of(0, 20);
Page<User> page = repository.page(pagination);
```

**Reusing Total Count** (avoids recalculating):
```java
Page<User> page1 = repository.page(Pagination.of(0, 20));
Page<User> page2 = repository.page(
    Pagination.of(1, 20, page1.getTotal())
);
```

**Virtual Total** (estimated count):
```java
Pagination pagination = Pagination.of(0, 20);
pagination.setVirtualTotal(true);
Page<User> page = repository.page(pagination);
```

### 6. Transaction Management

Hi-SQL supports Spring's `@Transactional` annotation:

```java
@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;

    @Transactional
    public void createUser(User user) {
        userRepository.insert(user);
        // Other operations...
    }
}
```

## Development Workflows

### Creating a New Repository

1. **Define Entity** (use JPA annotations):
```java
@Entity
@Getter @Setter
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private BigDecimal price;
}
```

2. **Create Repository Interface**:
```java
@Repository
public interface ProductRepository extends SQLCrudRepository<Product, Long> {
    @HiSql("select * from product where price > :minPrice")
    List<Product> findExpensive(BigDecimal minPrice);
}
```

3. **Use in Service**:
```java
@Service
public class ProductService {
    @Autowired
    private ProductRepository productRepository;

    public Product getById(Long id) {
        return productRepository.findById(id);
    }

    public List<Product> getExpensiveProducts(BigDecimal minPrice) {
        return productRepository.findExpensive(minPrice);
    }
}
```

### Adding Custom SQL Methods

**Option 1: Inline SQL** (simple queries):
```java
@HiSql("select * from user where enabled = :enabled order by create_time desc")
List<User> findEnabled(boolean enabled);
```

**Option 2: XML SQL** (complex queries):

1. Create `src/test/resources/hisql/UserRepository.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE SQL>
<SQL>
    <findComplex><![CDATA[
        select u.*, p.name as profile_name
        from user u
        left join profile p on u.profile_id = p.id
        where u.enabled = :enabled
        {accountFilter}
        order by u.create_time desc
    ]]></findComplex>
</SQL>
```

2. Reference in interface:
```java
@HiSql(name = "findComplex")
List<UserProfile> findComplex(boolean enabled, String accountFilter);
```

### Implementing Pagination

```java
@Repository
public interface UserRepository extends SQLCrudRepository<User, Long> {
    @HiSql("select * from user where enabled = :enabled")
    Page<User> findEnabledPage(Pagination pagination, boolean enabled);

    @HiSql("select * from user where enabled = :enabled")
    Next<User> findEnabledNext(Pagination pagination, boolean enabled);
}

// Usage
Pagination pagination = Pagination.of(0, 20, Sort.desc("createTime"));
Page<User> page = userRepository.findEnabledPage(pagination, true);

System.out.println("Total: " + page.getTotal());
System.out.println("Data: " + page.getData());
```

### Working with Composite Keys

1. **Define Composite Key Class**:
```java
@Getter @Setter
public class CompositeKey {
    private Long id;
    private String code;
}
```

2. **Define Entity**:
```java
@Entity
@IdClass(CompositeKey.class)
@Getter @Setter
public class CompositeTable {
    @Id
    private Long id;

    @Id
    private String code;

    private String name;
}
```

3. **Create Repository**:
```java
@Repository
public interface CompositeRepository extends SQLCrudRepository<CompositeTable, CompositeKey> {
}
```

### Custom ResultSet Conversion

To support custom types (e.g., `Pattern`, `UUID`, custom value objects):

1. **Create Custom Converter**:
```java
public class CustomResultSetConverter extends ResultSetConverter {
    {
        register(Pattern.class, (rs, i) -> Pattern.compile(rs.getString(i)));
        register(UUID.class, (rs, i) -> UUID.fromString(rs.getString(i)));
    }
}
```

2. **Configure**:
```java
@EnableHiSql(resultSetConverter = CustomResultSetConverter.class)
public class HiSqlConfig {
}
```

## Testing Conventions

### Test Structure

```
src/test/java/pers/clare/hisql/
├── data/
│   ├── entity/              # Test entities (User, CompositeTable, etc.)
│   ├── repository/          # Test repositories
│   └── HiSqlConfig.java     # Test configuration
├── performance/             # Performance benchmarks
├── repository/              # Repository tests
└── service/                 # Service tests
```

### Test Configuration

**Location**: `/home/user/hi-sql/src/test/java/pers/clare/hisql/data/HiSqlConfig.java`

```java
@EnableHiSql(
    resultSetConverter = CustomResultSetConverter.class,
    beanNamePrefix = "test",
    paginationMode = H2PaginationMode.class
)
public class HiSqlConfig {
}
```

### Test Database Configuration

**H2 In-Memory** (default for tests):
- Config: `src/test/resources/application-h2.yml`
- JDBC URL: `jdbc:h2:mem:testdb`
- Auto-create schema on startup

### Writing Tests

**Repository Test Example**:
```java
@SpringBootTest
@ActiveProfiles("h2")
class UserRepositoryTest {
    @Autowired
    private UserRepository userRepository;

    @Test
    void testFindById() {
        User user = new User();
        user.setAccount("test");
        user.setName("Test User");

        Long id = userRepository.insert(user);
        User found = userRepository.findById(id);

        assertNotNull(found);
        assertEquals("test", found.getAccount());
    }

    @Test
    void testPagination() {
        // Create test data
        for (int i = 0; i < 50; i++) {
            User user = new User();
            user.setAccount("user" + i);
            userRepository.insert(user);
        }

        // Test pagination
        Pagination pagination = Pagination.of(0, 20);
        Page<User> page = userRepository.page(pagination);

        assertEquals(50, page.getTotal());
        assertEquals(20, page.getData().size());
    }
}
```

## Code Style and Conventions

### Naming Conventions

1. **Entities**: Use singular nouns (e.g., `User`, not `Users`)
2. **Repositories**: `EntityNameRepository` (e.g., `UserRepository`)
3. **Services**: `EntityNameService` (e.g., `UserService`)
4. **Methods**: Follow Spring Data naming conventions where applicable
   - `findById`, `findAll`, `deleteById`
   - Custom: `findByAccount`, `findEnabled`, etc.

### SQL Conventions

1. **Use lowercase for SQL keywords in XML**:
```xml
<findAll><![CDATA[
    select * from user where enabled = :enabled
]]></findAll>
```

2. **Use parameter placeholders consistently**:
   - Named: `:paramName`
   - Dynamic: `{placeholder}`

3. **Avoid SQL injection**:
   - NEVER concatenate SQL strings externally
   - Always use `{placeholder}` for dynamic SQL
   - Use `SqlReplace` for conditional fragments

### Entity Conventions

1. **Use JPA annotations** for metadata:
```java
@Entity
@Table(name = "custom_table_name")
public class MyEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "custom_column", nullable = false)
    private String field;
}
```

2. **Use Lombok** for boilerplate reduction:
```java
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private Long id;
    private String name;
}
```

### Package Organization

1. **Group by layer**: `entity`, `repository`, `service`, `controller`
2. **Keep interfaces and implementations together** (Hi-SQL creates proxies)
3. **Use sub-packages for domains** in larger projects

## Build and Deployment

### Building the Project

```bash
# Clean and compile
mvn clean compile

# Run tests
mvn test

# Package JAR
mvn package

# Install to local repository
mvn install
```

### Maven Profiles

**Default Profile** - Standard build:
```bash
mvn clean package
```

**Release Profile** - Maven Central deployment:
```bash
mvn clean deploy -P release
```

Release profile includes:
- Javadoc generation
- Source attachment
- GPG signing
- Nexus staging

### Dependencies

**Core Runtime**:
- `spring-context`, `spring-jdbc`
- `log4j-api`
- `javax.persistence-api`

**Optional**:
- `lombok` (compile-time only)

**Testing**:
- `spring-boot-starter-test`
- `h2` (in-memory database)
- `mysql-connector-java` (MySQL testing)

### Publishing to Maven Central

The project is configured for Sonatype OSSRH:
- Repository: `https://s01.oss.sonatype.org/`
- Group ID: `io.github.babyblue94520`
- Artifact ID: `hi-sql`

## Common Tasks for AI Assistants

### Task: Add a new CRUD repository

1. Create entity with JPA annotations in `src/test/java/pers/clare/hisql/data/entity/`
2. Create repository interface extending `SQLCrudRepository` in `src/test/java/pers/clare/hisql/data/repository/`
3. Add `@Repository` annotation
4. No implementation needed - framework creates proxy

### Task: Add a custom query method

1. Identify repository interface
2. Add method with `@HiSql` annotation
3. For simple SQL, use inline: `@HiSql("select ...")`
4. For complex SQL, create XML file in `src/test/resources/hisql/`

### Task: Modify pagination behavior

1. Locate: `/home/user/hi-sql/src/main/java/pers/clare/hisql/page/`
2. Modify existing `PaginationMode` implementation (MySQL, H2, MSSQL)
3. Or create custom implementation
4. Configure via `@EnableHiSql(paginationMode = CustomPaginationMode.class)`

### Task: Add support for a new database type

1. Create new `PaginationMode` implementation in `/page/` package
2. Implement `buildPaginationSQL()` with database-specific LIMIT/OFFSET syntax
3. Implement `getVirtualTotal()` for estimated counts (optional)
4. Update configuration to use new mode

### Task: Debug SQL execution

1. Check method interception: `/home/user/hi-sql/src/main/java/pers/clare/hisql/method/SQLMethodFactory.java:428`
2. Check query building: `/home/user/hi-sql/src/main/java/pers/clare/hisql/query/SQLQueryBuilder.java`
3. Check store metadata: `/home/user/hi-sql/src/main/java/pers/clare/hisql/store/SQLCrudStore.java:108`
4. Enable SQL logging in `application.yml`:
```yaml
logging:
  level:
    pers.clare.hisql: DEBUG
```

### Task: Fix failing tests

1. Check test configuration: `/home/user/hi-sql/src/test/java/pers/clare/hisql/data/HiSqlConfig.java`
2. Verify H2 database config: `/home/user/hi-sql/src/test/resources/application-h2.yml`
3. Check entity definitions in `/home/user/hi-sql/src/test/java/pers/clare/hisql/data/entity/`
4. Review test SQL in `/home/user/hi-sql/src/test/resources/hisql/`

### Task: Add a new utility method

1. Locate appropriate util class in `/home/user/hi-sql/src/main/java/pers/clare/hisql/util/`
2. Common utils:
   - `ArgumentParseUtil`: Argument parsing
   - `ConnectionUtil`: JDBC operations
   - `ResultSetUtil`: ResultSet processing
   - `SQLQueryUtil`: Query building
3. Add static method following existing patterns

## Important Files Reference

### Configuration Entry Points
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/annotation/EnableHiSql.java`
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/annotation/HiSql.java`

### Core Repository System
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/repository/SQLRepository.java`
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/repository/SQLCrudRepository.java`
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/repository/SQLRepositoryScanner.java`

### Method Interception
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/method/SQLMethodFactory.java` (428 lines)
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/method/SQLProxyFactory.java`
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/method/SQLInjector.java`

### Data Store System
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/store/SQLStore.java`
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/store/SQLCrudStore.java` (108 lines)
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/store/SQLStoreFactory.java`

### Service Layer
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/service/SQLService.java`
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/service/SQLStoreService.java`

### Pagination
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/page/Pagination.java`
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/page/PaginationMode.java`
- `/home/user/hi-sql/src/main/java/pers/clare/hisql/page/MySQLPaginationMode.java`

### Test Examples
- `/home/user/hi-sql/src/test/java/pers/clare/hisql/data/repository/CustomRepository.java` (116 lines)
- `/home/user/hi-sql/src/test/java/pers/clare/hisql/repository/SQLCrudRepositoryTest.java`
- `/home/user/hi-sql/src/test/resources/hisql/CustomRepository.xml`

## Security Considerations

### SQL Injection Prevention

1. **ALWAYS use parameterized queries**:
```java
// CORRECT
@HiSql("select * from user where account = :account")
User findByAccount(String account);

// WRONG - vulnerable to SQL injection
@HiSql("select * from user where account = '" + account + "'")
```

2. **Use dynamic placeholders for structural changes**:
```java
// CORRECT
@HiSql("select * from user where 1=1 {condition}")
List<User> find(String condition, Object... params);

// Usage with proper parameterization
repository.find("and account = :account", accountValue);
```

3. **Never concatenate user input into SQL**:
```java
// WRONG
String condition = "and account = '" + userInput + "'";
repository.find(condition);

// CORRECT
repository.find("and account = :account", userInput);
```

### Transaction Isolation

Use Spring's `@Transactional` with appropriate isolation levels:
```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public void criticalOperation() {
    // Database operations
}
```

## Performance Optimization

### 1. Use Virtual Totals for Large Datasets
```java
Pagination pagination = Pagination.of(0, 20);
pagination.setVirtualTotal(true);  // Uses EXPLAIN instead of COUNT(*)
```

### 2. Reuse Total Counts in Pagination
```java
Page<User> page1 = repository.page(Pagination.of(0, 20));
Page<User> page2 = repository.page(
    Pagination.of(1, 20, page1.getTotal())
);
```

### 3. Batch Operations
```java
// Use insertAll instead of multiple inserts
List<User> users = Arrays.asList(user1, user2, user3);
repository.insertAll(users);
```

### 4. Use Specific Column Selection
```java
// Better performance
@HiSql("select id, name from user where enabled = :enabled")
List<UserSimple> findEnabled(boolean enabled);

// Avoid when possible
@HiSql("select * from user where enabled = :enabled")
List<User> findEnabled(boolean enabled);
```

## Troubleshooting Guide

### Issue: Repository not found/injected

**Cause**: Package not scanned by `@EnableHiSql`

**Solution**: Add package to scan configuration:
```java
@EnableHiSql(basePackages = {"com.example.repository"})
```

### Issue: SQL not found in XML

**Cause**: XML file naming or location incorrect

**Solution**:
1. Ensure XML file name matches repository class name
2. Place in `resources/hisql/` directory
3. Use `@HiSql(name = "methodName")` to reference specific tag

### Issue: ResultSet mapping fails

**Cause**: Type mismatch or missing converter

**Solution**:
1. Verify entity field types match database types
2. Create custom `ResultSetConverter` for special types
3. Use `@Column(name = "...")` to map different names

### Issue: Pagination SQL incorrect

**Cause**: Wrong PaginationMode for database

**Solution**: Configure correct mode:
```java
@EnableHiSql(paginationMode = MySQLPaginationMode.class)
```

## Git Workflow for AI Assistants

When working on this repository:

1. **Always work on feature branches**: `claude/claude-md-mhzn6bk5j5pxu2as-01Xrq9HnJndkw6VoDGNxjukq`
2. **Commit frequently** with descriptive messages
3. **Follow commit message style** from git log:
   - `feat(Component): add new feature`
   - `fix(Component): fix bug`
   - `refactor(Component): refactor code`
   - `chore: maintenance task`
4. **Test before committing**: `mvn test`
5. **Push to feature branch**: `git push -u origin <branch-name>`

## Questions and Further Reading

- **README.md**: User-facing documentation and quick start guide
- **Tests**: Examples of all features in `src/test/java/pers/clare/hisql/`
- **GitHub**: https://github.com/babyblue94520/hi-sql

## Last Updated

This CLAUDE.md was generated on 2025-11-15 and reflects the current state of the hi-sql repository at commit d22ca75.
