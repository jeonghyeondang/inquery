
package ai.inquery.spi.sql;

import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.SqlBuilder;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.config.DriverConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 */
@Slf4j
public class InqueryContext {
    private static final ThreadLocal<ConnectInfo> CONNECT_INFO_THREAD_LOCAL = new ThreadLocal<>();
    private static final ThreadLocal<String> QUERY_SOURCE_THREAD_LOCAL = new ThreadLocal<>();

    /**
     * Query source constants
     */
    public static final String SOURCE_WORKSPACE = "WORKSPACE";
    public static final String SOURCE_AI_CHAT = "AI_CHAT";



    public static Map<String, Plugin> PLUGIN_MAP = new ConcurrentHashMap<>();

    static {
        ServiceLoader<Plugin> s = ServiceLoader.load(Plugin.class);
        Iterator<Plugin> iterator = s.iterator();
        while (iterator.hasNext()) {
            Plugin plugin = iterator.next();
            PLUGIN_MAP.put(plugin.getDBConfig().getDbType(), plugin);
        }
    }

    public static DriverConfig getDefaultDriverConfig(String dbType) {
        return PLUGIN_MAP.get(dbType).getDBConfig().getDefaultDriverConfig();
    }

    public static SqlBuilder getSqlBuilder() {
        return PLUGIN_MAP.get(getConnectInfo().getDbType()).getMetaData().getSqlBuilder();
    }

    /**
     * Get the ContentContext of the current thread
     *
     * @return
     */
    public static ConnectInfo getConnectInfo() {
        return CONNECT_INFO_THREAD_LOCAL.get();
    }

    public static MetaData getMetaData() {
        return PLUGIN_MAP.get(getConnectInfo().getDbType()).getMetaData();
    }

    public static MetaData getMetaData(String dbType) {
        if (StringUtils.isBlank(dbType)) {
            return getMetaData();
        }
        return PLUGIN_MAP.get(dbType).getMetaData();
    }

    public static DBConfig getDBConfig(String dbType) {
        return PLUGIN_MAP.get(dbType).getDBConfig();
    }

    public static DBConfig getDBConfig() {
        return PLUGIN_MAP.get(getConnectInfo().getDbType()).getDBConfig();
    }

    public static DBManage getDBManage() {
        return PLUGIN_MAP.get(getConnectInfo().getDbType()).getDBManage();
    }

    public static Connection getConnection() {
//        ConnectInfo connectInfo = getConnectInfo();
//        Connection connection = connectInfo.getConnection();
//        try {
//            if (connection == null || connection.isClosed()) {
//                synchronized (connectInfo) {
//                    connection = connectInfo.getConnection();
//                    try {
//                        if (connection != null && !connection.isClosed()) {
//                            log.info("get connection from cache");
//                            return connection;
//                        } else {
//                            log.info("get connection from db begin");
//                            connection = getDBManage().getConnection(connectInfo);
//                            log.info("get connection from db end");
//                        }
//                    } catch (SQLException e) {
//                        log.error("get connection error", e);
//                        log.info("get connection from db begin2");
//                        connection = getDBManage().getConnection(connectInfo);
//                        log.info("get connection from db end2");
//                    }
//                    connectInfo.setConnection(connection);
//                }
//            }
//        } catch (SQLException e) {
//            log.error("get connection error", e);
//        }
        return ConnectionPool.getConnection(getConnectInfo());
    }


    public static String getDbVersion() {
        ConnectInfo connectInfo = getConnectInfo();
        String dbVersion = connectInfo.getDbVersion();
        if (dbVersion == null) {
            synchronized (connectInfo) {
                if (connectInfo.getDbVersion() != null) {
                    return connectInfo.getDbVersion();
                } else {
                    dbVersion = SQLExecutor.getInstance().getDbVersion(getConnection());
                    connectInfo.setDbVersion(dbVersion);
                    return connectInfo.getDbVersion();
                }
            }
        } else {
            return dbVersion;
        }

    }


    /**
     * Set context
     *
     * @param info
     */
    public static void putContext(ConnectInfo info) {
        DriverConfig config = info.getDriverConfig();
        if (config == null) {
            config = getDefaultDriverConfig(info.getDbType());
            info.setDriverConfig(config);
        }
        CONNECT_INFO_THREAD_LOCAL.set(info);
    }

    /**
     * Set context
     */
    public static void removeContext() {
        ConnectInfo connectInfo = CONNECT_INFO_THREAD_LOCAL.get();
        if (connectInfo != null) {
//            connectInfo.close();
            CONNECT_INFO_THREAD_LOCAL.remove();
            ConnectionPool.close(connectInfo);
        }
    }

    public static void close() {
        removeContext();
    }

    /**
     * Set query source (WORKSPACE or AI_CHAT)
     *
     * @param source the source of the query
     */
    public static void setQuerySource(String source) {
        QUERY_SOURCE_THREAD_LOCAL.set(source);
    }

    /**
     * Get query source, defaults to WORKSPACE if not set
     *
     * @return the source of the query
     */
    public static String getQuerySource() {
        String source = QUERY_SOURCE_THREAD_LOCAL.get();
        return source != null ? source : SOURCE_WORKSPACE;
    }

    /**
     * Remove query source from thread local
     */
    public static void removeQuerySource() {
        QUERY_SOURCE_THREAD_LOCAL.remove();
    }

}
