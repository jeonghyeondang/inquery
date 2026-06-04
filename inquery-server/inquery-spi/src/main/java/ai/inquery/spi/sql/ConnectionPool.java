package ai.inquery.spi.sql;

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ConnectionPool {

    private static ConcurrentHashMap<String, ConnectInfo> CONNECTION_MAP = new ConcurrentHashMap<>();

    // Connection idle timeout: 2 hours (Snowflake session default is 4 hours)
    private static final long CONNECTION_IDLE_TIMEOUT_MS = 1000 * 60 * 60 * 2; // 2 hours
    // Check interval: 30 minutes
    private static final long CHECK_INTERVAL_MS = 1000 * 60 * 30; // 30 minutes

    static {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS);
                    log.info("CONNECTION_MAP size:{}",CONNECTION_MAP.size());
                    CONNECTION_MAP.forEach((k, v) -> {
                        log.info("CONNECTION_key:{},value:{}",k,v.getRefCount());
                        if (v.getLastAccessTime().getTime() + CONNECTION_IDLE_TIMEOUT_MS < System.currentTimeMillis() && v.getRefCount() == 0) {
                            try {
                                Connection connection = v.getConnection();
                                if (connection != null) {
                                    connection.close();
                                    CONNECTION_MAP.remove(k);
                                }
                            } catch (SQLException e) {
                                log.error("close connection error", e);
                            }
                        }
                    });
                } catch (InterruptedException e) {
                    log.error("close connection error", e);
                }
            }
        }).start();

    }

    public static synchronized void removeConnection(Long datasourceId) {
        CONNECTION_MAP.forEach((k, v) -> {
            if (k.contains(String.valueOf(datasourceId))) {
                try {
                    Connection connection = v.getConnection();
                    int refCount = v.getRefCount();

                    // Only close connection if no other operations are using it
                    if (refCount > 0) {
                        log.info("Skipping connection removal for key:{} - still in use by {} operation(s)", k, refCount);
                        return; // Skip this connection, it's still in use
                    }

                    if (connection != null) {
                        connection.close();
                        CONNECTION_MAP.remove(k);
                    }
                } catch (SQLException e) {
                    log.error("close connection error", e);
                }
            }
        });
    }


    public static Connection getConnection(ConnectInfo connectInfo) {
        Connection connection = connectInfo.getConnection();

        try {
            if (connection != null && !connection.isClosed()) {
                log.info("get connection from loacl");
                return connection;
            }

            String key = connectInfo.getKey();

            // If consoleId is 0 or null (e.g., AI collection, Deep Research), try to reuse any existing connection
            // for the same dataSourceId to avoid creating a new Snowflake connection
            Long consoleId = connectInfo.getConsoleId();
            if (consoleId == null || consoleId == 0L) {
                // First try: Match by loginUser + dataSourceId (same user's connections)
                String baseKeyPrefix = "loginUser:" + connectInfo.getLoginUser() + "_dataSourceId:" + connectInfo.getDataSourceId() + "_consoleId:";
                Connection foundConn = findConnectionByPrefix(connectInfo, baseKeyPrefix, consoleId);
                if (foundConn != null) {
                    return foundConn;
                }
                
                // Second try: Match by dataSourceId only (any user's connection for same database)
                // This allows Deep Research (loginUser=0) to reuse connections created by actual users
                String dataSourcePattern = "_dataSourceId:" + connectInfo.getDataSourceId() + "_";
                for (Map.Entry<String, ConnectInfo> entry : CONNECTION_MAP.entrySet()) {
                    if (entry.getKey().contains(dataSourcePattern)) {
                        try {
                            Connection existingConn = entry.getValue().getConnection();
                            if (existingConn != null && !existingConn.isClosed()) {
                                log.info("Reusing connection from different user for dataSourceId: {} (key: {})", 
                                        connectInfo.getDataSourceId(), entry.getKey());
                                connectInfo.setConnection(existingConn);
                                connectInfo.setConsoleId(entry.getValue().getConsoleId());
                                connectInfo.setLoginUser(entry.getValue().getLoginUser());
                                entry.getValue().incrementRefCount();
                                entry.getValue().setLastAccessTime(new Date());
                                return existingConn;
                            }
                        } catch (SQLException e) {
                            log.debug("Error checking existing connection: {}", entry.getKey());
                        }
                    }
                }
                log.info("No existing connection found for dataSourceId: {}, will create new one", connectInfo.getDataSourceId());
            }
            ConnectInfo lock = CONNECTION_MAP.computeIfAbsent(key, k -> connectInfo.copy());
            try {
                synchronized (lock) {
                    connection = connectInfo.getConnection();
                    if (connection != null && !connection.isClosed()) {
                        log.info("get connection from loacl");
                        return connection;
                    }

                    int n = lock.incrementRefCount();
                    // Always try to reuse cached connection first, regardless of refCount
                    // This avoids creating new connections for concurrent requests
                    connection = lock.getConnection();

                    if (connection != null && !connection.isClosed()) {
                        log.info("get connection from cache (refCount: {})", n);
                        connectInfo.setConnection(connection);
                        lock.setLastAccessTime(new Date());
                        return connection;
                    }
                    
                    // Cached connection is null or closed, need to create a new one
                    // Always create new connection regardless of refCount (fixes race condition bug)
                    log.info("get connection from db begin (refCount: {}, cached connection was null/closed)", n);
                    connection = InqueryContext.getDBManage().getConnection(connectInfo);

                    // Save to cache and local
                    lock.setConnection(connection);
                    lock.setLastAccessTime(new Date());
                    connectInfo.setConnection(connection);
                    log.info("get connection from db end");
                    // Notify waiting threads that connection is ready
                    lock.notifyAll();
                    return connection;

                }
            } catch (SQLException e) {
                log.error("get connection error", e);
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            }
        } catch (SQLException e) {
            log.error("get connection error", e);
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (Exception e1) {
                log.error("", e1);
            }
        }
        return null;
    }

    public static void close(ConnectInfo connectInfo) {
        String key = connectInfo.getKey();
        try {
            Connection currentConnection = connectInfo.getConnection();
            // If the current connection is already closed, no need to close it again
            if (currentConnection == null || currentConnection.isClosed()) {
                log.info("connection is already closed, key:{}, n:{}", connectInfo.getKey(), connectInfo.getRefCount());
                return;
            }
        } catch (SQLException e) {
            log.error("connection close error",e);
        }
        ConnectInfo lock = CONNECTION_MAP.get(key);
        if (lock != null) {
            synchronized (lock) {
                int n = lock.decrementRefCount();
                if (n == 0) {
                    // No other threads using this connection, keep it in cache for reuse
                    lock.setLastAccessTime(new Date());
                    lock.setConnection(connectInfo.getConnection());
                    log.debug("connection returned to pool, key:{}", key);
                } else {
                    // Other threads still using this connection, just decrement refCount
                    // DO NOT close the connection as it's still in use
                    log.debug("connection still in use by {} other thread(s), key:{}", n, key);
                }
            }
        } else {
            // No pool entry, close the connection directly
            connectInfo.close();
        }
    }
    
    /**
     * Helper method to find a connection by key prefix.
     */
    private static Connection findConnectionByPrefix(ConnectInfo connectInfo, String prefix, Long consoleId) {
        for (Map.Entry<String, ConnectInfo> entry : CONNECTION_MAP.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                try {
                    Connection existingConn = entry.getValue().getConnection();
                    if (existingConn != null && !existingConn.isClosed()) {
                        log.info("Reusing existing connection from key: {} for AI operation (consoleId={})", 
                                entry.getKey(), consoleId);
                        connectInfo.setConnection(existingConn);
                        connectInfo.setConsoleId(entry.getValue().getConsoleId());
                        entry.getValue().incrementRefCount();
                        entry.getValue().setLastAccessTime(new Date());
                        return existingConn;
                    }
                } catch (SQLException e) {
                    log.debug("Error checking existing connection: {}", entry.getKey());
                }
            }
        }
        return null;
    }
}
