package ai.inquery.server.domain.repository;

import ai.inquery.server.domain.repository.mapper.AiChatAttachmentMapper;
import ai.inquery.server.domain.repository.mapper.AiChatMessageAttachmentMapper;
import ai.inquery.server.domain.repository.mapper.AiChatMessageMapper;
import ai.inquery.server.domain.repository.mapper.AiChatRoomMapper;
import ai.inquery.server.domain.repository.mapper.TableLineageMapper;
import ai.inquery.server.tools.common.model.ConfigJson;
import ai.inquery.server.tools.common.util.ConfigUtils;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.logging.slf4j.Slf4jImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Slf4j
public class Dbutils {

    private static final ThreadLocal<SqlSession> SQL_SESSION_THREAD_LOCAL = new ThreadLocal<>();

    public static void init() {
    }

    public static void setSession() {
        SqlSession session = sqlSessionFactory.openSession(true);
        SQL_SESSION_THREAD_LOCAL.set(session);
    }

    public static void removeSession() {
        SqlSession session = SQL_SESSION_THREAD_LOCAL.get();
        if (session != null) {
            session.close();
        }
        SQL_SESSION_THREAD_LOCAL.remove();
    }

    /**
     * Check whether a SqlSession is already bound to the current thread.
     */
    public static boolean hasSession() {
        return SQL_SESSION_THREAD_LOCAL.get() != null;
    }

    private static SqlSessionFactory sqlSessionFactory;

    static {
        try {
            before();
        } catch (IOException e) {
            log.error("Dbutils error", e);
        }
    }


    private static void before() throws IOException {
        SqlSessionFactoryBuilder builder = new SqlSessionFactoryBuilder();
        //This is the configuration object of mybatis-plus, which enhances the configuration of mybatis.
        MybatisConfiguration configuration = new MybatisConfiguration();
        //This is the initial configuration, this part of the code will be added later
        initConfiguration(configuration);
        //This is the initialization connector, such as the paging plug-in of mybatis-plus
        configuration.addInterceptor(initInterceptor());
        //Configuration log implementation
        configuration.setLogImpl(Slf4jImpl.class);
        //Scan the package where the mapper interface is located
        configuration.addMappers("ai.inquery.server.domain.repository.mapper");
        // Explicitly register AiChatRoomMapper and AiChatMessageMapper to ensure they're found (may not be found by package scan in jar files)
        try {
            configuration.addMapper(AiChatRoomMapper.class);
            log.info("AiChatRoomMapper registered successfully");
        } catch (Exception e) {
            log.warn("Failed to register AiChatRoomMapper explicitly: {}", e.getMessage());
        }
        try {
            configuration.addMapper(AiChatMessageMapper.class);
            log.info("AiChatMessageMapper registered successfully");
        } catch (Exception e) {
            log.warn("Failed to register AiChatMessageMapper explicitly: {}", e.getMessage());
        }
        try {
            configuration.addMapper(TableLineageMapper.class);
            log.info("TableLineageMapper registered successfully");
        } catch (Exception e) {
            log.warn("Failed to register TableLineageMapper explicitly: {}", e.getMessage());
        }
        try {
            configuration.addMapper(AiChatAttachmentMapper.class);
            log.info("AiChatAttachmentMapper registered successfully");
        } catch (Exception e) {
            log.warn("Failed to register AiChatAttachmentMapper explicitly: {}", e.getMessage());
        }
        try {
            configuration.addMapper(AiChatMessageAttachmentMapper.class);
            log.info("AiChatMessageAttachmentMapper registered successfully");
        } catch (Exception e) {
            log.warn("Failed to register AiChatMessageAttachmentMapper explicitly: {}", e.getMessage());
        }
        try {
            configuration.addMapper(ai.inquery.server.domain.repository.mapper.ReferenceDocumentMapper.class);
            log.info("ReferenceDocumentMapper registered successfully");
        } catch (Exception e) {
            log.warn("Failed to register ReferenceDocumentMapper explicitly: {}", e.getMessage());
        }
        //Globalconfig required to build mybatis-plus
        GlobalConfig globalConfig = GlobalConfigUtils.getGlobalConfig(configuration);
        //This parameter will automatically generate the basic method mapping that implements baseMapper.
        globalConfig.setSqlInjector(new DefaultSqlInjector());
        //Set up id generator
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        //Set super class mapper
        globalConfig.setSuperMapperClass(BaseMapper.class);
        DataSource dataSource = initDataSource();
        Environment environment = new Environment("1", new JdbcTransactionFactory(), dataSource);
        configuration.setEnvironment(environment);
        //Set data source
        registryMapperXml(configuration, "mapper");
        //Build sqlSessionFactory
        sqlSessionFactory = builder.build(configuration);

        initFlyway(dataSource);
        
        // Ensure critical tables exist (fallback for migration issues)
        ensureCriticalTablesExist(dataSource);

    }

    private static void initFlyway(DataSource dataSource) {
        String currentVersion = ConfigUtils.getLocalVersion();
        ConfigJson configJson = ConfigUtils.getConfig();
        // Represents that the current version has been successfully launched
        if (StringUtils.isNotBlank(currentVersion) && configJson != null && StringUtils.equals(currentVersion,
                configJson.getLatestStartupSuccessVersion())) {
            return;
        } else {
            Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

            try {
                flyway.migrate();
            } catch (Exception e) {
                // In local dev/test, it's common to hit failed migrations or checksum changes while iterating.
                // Auto-repair and retry once to keep the app bootable.
                String environment = StringUtils.defaultString(System.getProperty("spring.profiles.active"), "dev");
                if ("dev".equalsIgnoreCase(environment) || "test".equalsIgnoreCase(environment)
                        || "desktop".equalsIgnoreCase(environment)) {
                    log.warn("Flyway migrate failed in {} profile, attempting repair and retry. cause={}", environment, e.getMessage());
                    try {
                        flyway.repair();
                        flyway.migrate();
                    } catch (Exception retryEx) {
                        // Re-throw the original failure if retry also fails
                        throw retryEx;
                    }
                } else {
                    throw e;
                }
            }

            configJson.setLatestStartupSuccessVersion(currentVersion);
            ConfigUtils.setConfig(configJson);
        }
    }
    
    /**
     * Ensure critical tables exist even if Flyway migrations were skipped or failed.
     * This is a fallback mechanism for development environments.
     */
    private static void ensureCriticalTablesExist(DataSource dataSource) {
        String environment = StringUtils.defaultString(System.getProperty("spring.profiles.active"), "dev");
        if (!"dev".equalsIgnoreCase(environment) && !"test".equalsIgnoreCase(environment)
                && !"desktop".equalsIgnoreCase(environment)) {
            return;
        }
        
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            
            // Create deep_research_session table if not exists (PostgreSQL syntax)
            String createDeepResearchSession = """
                CREATE TABLE IF NOT EXISTS deep_research_session (
                    id BIGSERIAL NOT NULL,
                    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    chat_room_id BIGINT NOT NULL,
                    question TEXT NOT NULL,
                    research_plan TEXT,
                    md_content TEXT,
                    report_json TEXT,
                    status VARCHAR(20) NOT NULL DEFAULT 'PLANNING',
                    current_iteration INTEGER DEFAULT 0,
                    total_queries_executed INTEGER DEFAULT 0,
                    error_message TEXT,
                    user_id BIGINT,
                    PRIMARY KEY (id)
                )
                """;
            stmt.execute(createDeepResearchSession);
            
            // Create indexes (IF NOT EXISTS is PostgreSQL compatible)
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_drs_chat_room_id ON deep_research_session(chat_room_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_drs_status ON deep_research_session(status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_drs_user_id ON deep_research_session(user_id)");
            
            log.info("Ensured deep_research_session table exists");
        } catch (Exception e) {
            log.warn("Failed to ensure critical tables exist: {}", e.getMessage());
        }
    }

    /**
     * Initial configuration
     *
     * @param configuration
     */
    private static void initConfiguration(MybatisConfiguration configuration) {
        //Turn on camel case conversion
        configuration.setMapUnderscoreToCamelCase(true);
        //Configure adding data to automatically return the data primary key
        configuration.setUseGeneratedKeys(true);
    }

    /**
     * Initialize data source
     *
     * @return
     */
    private static DataSource initDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        String environment = StringUtils.defaultString(System.getProperty("spring.profiles.active"), "dev");
        String dbHost = System.getenv("DB_HOST") != null ? System.getenv("DB_HOST") : "localhost";
        String dbPort = System.getenv("DB_PORT") != null ? System.getenv("DB_PORT") : "5432";
        String dbUsername = System.getenv("DB_USERNAME");
        String dbPassword = System.getenv("DB_PASSWORD");
        if ("dev".equalsIgnoreCase(environment)) {
            dataSource.setJdbcUrl("jdbc:postgresql://localhost:15432/inquery_desktop");
            dataSource.setUsername("inquery");
            dataSource.setPassword("inquery");
        } else if ("test".equalsIgnoreCase(environment)) {
            dataSource.setJdbcUrl("jdbc:postgresql://localhost:5432/inquery_test");
            dataSource.setUsername(System.getProperty("user.name"));
        } else if ("desktop".equalsIgnoreCase(environment)) {
            dataSource.setJdbcUrl("jdbc:postgresql://localhost:15432/inquery_desktop");
            dataSource.setUsername("inquery");
            dataSource.setPassword("inquery");
        } else {
            String dbName = System.getenv("DB_NAME") != null ? System.getenv("DB_NAME") : "inquery";
            dataSource.setJdbcUrl("jdbc:postgresql://" + dbHost + ":" + dbPort + "/" + dbName);
            if (dbUsername != null) dataSource.setUsername(dbUsername);
            else dataSource.setUsername(System.getProperty("user.name"));
            if (dbPassword != null) dataSource.setPassword(dbPassword);
        }
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setIdleTimeout(60000);
        dataSource.setAutoCommit(true);
        dataSource.setMaximumPoolSize(500);
        dataSource.setMinimumIdle(1);
        dataSource.setMaxLifetime(60000 * 10);
        dataSource.setConnectionTestQuery("SELECT 1");
        return dataSource;

    }

    /**
     * Initialize interceptor
     *
     * @return
     */
    private static Interceptor initInterceptor() {
        //Create mybatis-plus plug-in object
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        //Build a pagination plugin
        PaginationInnerInterceptor paginationInnerInterceptor = new PaginationInnerInterceptor();
        paginationInnerInterceptor.setDbType(DbType.POSTGRE_SQL);
        paginationInnerInterceptor.setOverflow(true);
        // No max limit - user can fetch as many rows as needed
        paginationInnerInterceptor.setMaxLimit(-1L);
        interceptor.addInnerInterceptor(paginationInnerInterceptor);
        return interceptor;
    }

    /**
     * Parse mapper.xml file
     *
     * @param configuration
     * @param classPath
     * @throws IOException
     */
    private static void registryMapperXml(MybatisConfiguration configuration, String classPath) throws IOException {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> mapper = contextClassLoader.getResources(classPath);
        while (mapper.hasMoreElements()) {
            URL url = mapper.nextElement();
            if (url.getProtocol().equals("file")) {
                String path = url.getPath();
                File file = new File(path);
                File[] files = file.listFiles();
                for (File f : files) {
                    FileInputStream in = new FileInputStream(f);
                    XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(in, configuration, f.getPath(), configuration.getSqlFragments());
                    xmlMapperBuilder.parse();
                    in.close();
                }
            } else {
                JarURLConnection urlConnection = (JarURLConnection) url.openConnection();
                JarFile jarFile = urlConnection.getJarFile();
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry jarEntry = entries.nextElement();
                    if (jarEntry.getName().endsWith("Mapper.xml")) {
                        InputStream in = jarFile.getInputStream(jarEntry);
                        XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(in, configuration, jarEntry.getName(), configuration.getSqlFragments());
                        xmlMapperBuilder.parse();
                        in.close();
                    }
                }
            }
        }
    }

    public static <T> T getMapper(Class<T> clazz) {
        SqlSession session = SQL_SESSION_THREAD_LOCAL.get();
        return session.getMapper(clazz);
    }

//    public static void main(String[] args) {
//
//        ExecutorService e = Executors.newCachedThreadPool();
//        for (int i = 0; i < 20; i++) {
//            e.execute(() -> {
//                SqlSession session = sqlSessionFactory.openSession();
//                DataSourceMapper mapper = session.getMapper(DataSourceMapper.class);
//                DataSourceDO dataSourceDO = mapper.selectById(1);
//                session.close();
//                System.out.println(JSON.toJSONString(dataSourceDO));
//            });
//        }
//
//    }
}
