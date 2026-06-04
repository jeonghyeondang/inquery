package ai.inquery.server.domain.core.cache;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.Weigher;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cache.support.NullValue;

/**
 * It will only be stored in memory
 *
 */
public class MemoryCacheManage {

    private static final byte[] NULL_BYTES = SerializationUtils.serialize((NullValue)NullValue.INSTANCE);
    private static final ConcurrentHashMap<String, Object> LOCK_MAP = new ConcurrentHashMap<>();

    private static final Cache<String, byte[]> CACHE = CacheBuilder.newBuilder()
        // 5M
        .maximumWeight(5 * 1024 * 1024)
        .weigher((Weigher<String, byte[]>)(key, value) -> value.length)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build();

    /**
     * Retrieve a value from the cache, and if not, query it
     * The timeout is fixed at 10 minutes
     *
     * @param key
     * @param queryData
     * @param <T>
     * @return
     */
    public static <T extends Serializable> T computeIfAbsent(String key, Supplier<T> queryData) {
        if (key == null) {
            return null;
        }
        T data = get(key);
        if (data != null) {
            return data;
        }
        Object lock = LOCK_MAP.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            data = get(key);
            if (data != null) {
                return data;
            }

            T value = queryData.get();
            put(key, value);
            return value;
        }
    }

    /**
     * Get a data from cache
     *
     * @param key
     * @param <T>
     * @return
     */
    public static <T> T get(String key) {
        if (StringUtils.isBlank(key)) {
            return null;
        }
        byte[] bytes = CACHE.getIfPresent(key);
        if (bytes == null) {
            return null;
        }
        T data = SerializationUtils.deserialize(bytes);
        if (NullValue.INSTANCE.equals(data)) {
            return null;
        }
        return data;
    }

    /**
     * Put a data from cache
     * The timeout is fixed at 10 minutes
     *
     * @param key
     * @param value
     */
    public static void put(String key, Serializable value) {
        if (key == null) {
            return;
        }
        if (value == null) {
            CACHE.put(key, NULL_BYTES);
        } else {
            CACHE.put(key, SerializationUtils.serialize(value));
        }
    }

    /**
     * Invalidate (remove) a key from cache
     *
     * @param key the cache key to invalidate
     */
    public static void invalidate(String key) {
        if (key == null) {
            return;
        }
        CACHE.invalidate(key);
    }

}
