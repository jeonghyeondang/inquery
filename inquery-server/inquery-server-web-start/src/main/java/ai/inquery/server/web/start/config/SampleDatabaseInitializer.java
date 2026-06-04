package ai.inquery.server.web.start.config;

import ai.inquery.server.domain.api.enums.RoleCodeEnum;
import ai.inquery.server.domain.core.event.UserCreatedEvent;
import ai.inquery.server.domain.core.util.DesUtil;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.DataCatalogTableDO;
import ai.inquery.server.domain.repository.entity.DataSourceDO;
import ai.inquery.server.domain.repository.entity.InqueryUserDO;
import ai.inquery.server.domain.repository.mapper.DataCatalogTableMapper;
import ai.inquery.server.domain.repository.mapper.DataSourceMapper;
import ai.inquery.server.domain.repository.mapper.InqueryUserMapper;
import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import ai.inquery.server.web.api.http.GatewayClientService;
import org.springframework.core.io.ClassPathResource;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.model.output.Response;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Creates and seeds a sample e-commerce database on startup.
 * Provides a method to auto-create a sample connection for new users.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SampleDatabaseInitializer {

    private final DataSource dataSource;
    private final Environment env;

    public static final String SAMPLE_DB_NAME = "inquery_sample";
    public static final String SAMPLE_ALIAS = "Sample E-Commerce DB";
    private static final String SAMPLE_USER = "sample_readonly";
    private static final String SAMPLE_PASSWORD = "sample_readonly_pw";

    /**
     * Bump when sample DDL, sample-catalog-seed.sql, or sample-vector-schemas.txt changes so
     * startup can recreate inquery_sample and resync app catalog + vectors.
     */
    private static final int SAMPLE_SEED_VERSION = 2;

    private String getSampleJdbcUrl() {
        String appUrl = env.getProperty("spring.datasource.url", "jdbc:postgresql://localhost:15432/inquery_desktop");
        return appUrl.replaceFirst("/[^/]*$", "/" + SAMPLE_DB_NAME);
    }

    private String getDbUsername() {
        return env.getProperty("spring.datasource.username", "inquery");
    }

    private String getDbPassword() {
        return env.getProperty("spring.datasource.password", "inquery");
    }

    private String getServerUrl() {
        String appUrl = env.getProperty("spring.datasource.url", "jdbc:postgresql://localhost:15432/inquery_desktop");
        return appUrl.replaceFirst("/[^/]*$", "/postgres");
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void initSampleDatabase() {
        try {
            createDatabaseIfNotExists();
            boolean sampleDbReseeded = seedTablesAndData();
            repairAllBrokenSampleConnections(sampleDbReseeded);
            createSampleConnectionsForExistingUsers();
            log.info("Sample database '{}' initialized successfully", SAMPLE_DB_NAME);
        } catch (Exception e) {
            log.warn("Failed to initialize sample database (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * @param sampleDbReseeded true if {@link #seedTablesAndData()} recreated {@code inquery_sample} public schema
     */
    private void repairAllBrokenSampleConnections(boolean sampleDbReseeded) {
        try {
            Dbutils.setSession();
            DataSourceMapper mapper = Dbutils.getMapper(DataSourceMapper.class);
            DataCatalogTableMapper catalogMapper = Dbutils.getMapper(DataCatalogTableMapper.class);

            LambdaQueryWrapper<DataSourceDO> query = new LambdaQueryWrapper<>();
            query.eq(DataSourceDO::getAlias, SAMPLE_ALIAS);
            java.util.List<DataSourceDO> records = mapper.selectList(query);
            for (DataSourceDO record : records) {
                boolean needsRepair = record.getDriverConfig() == null
                        || record.getDriverConfig().isBlank()
                        || !SAMPLE_USER.equals(record.getUserName());
                if (needsRepair) {
                    repairExistingRecord(mapper, record);
                }

                LambdaQueryWrapper<DataCatalogTableDO> catalogQuery = new LambdaQueryWrapper<>();
                catalogQuery.eq(DataCatalogTableDO::getDataSourceId, record.getId());
                Long catalogCount = catalogMapper.selectCount(catalogQuery);
                if (sampleDbReseeded) {
                    deleteSampleAppCatalogLineageAndVectors(record.getId());
                    seedCatalogForConnection(record.getId(), record.getUserId());
                    seedVectorEmbeddings(record.getId(), true);
                } else {
                    if (catalogCount == null || catalogCount == 0) {
                        seedCatalogForConnection(record.getId(), record.getUserId());
                    }
                    seedVectorEmbeddings(record.getId(), false);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to repair sample connections: {}", e.getMessage());
        } finally {
            Dbutils.removeSession();
        }
    }

    private void createDatabaseIfNotExists() throws SQLException {
        try (Connection conn = DriverManager.getConnection(getServerUrl(), getDbUsername(), getDbPassword())) {
            Statement stmt = conn.createStatement();

            ResultSet rs = stmt.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '" + SAMPLE_DB_NAME + "'");
            if (!rs.next()) {
                stmt.execute("CREATE DATABASE " + SAMPLE_DB_NAME);
                log.info("Created sample database '{}'", SAMPLE_DB_NAME);
            }
            rs.close();

            rs = stmt.executeQuery(
                    "SELECT 1 FROM pg_roles WHERE rolname = '" + SAMPLE_USER + "'");
            boolean userExists = rs.next();
            rs.close();

            if (!userExists) {
                stmt.execute("CREATE USER " + SAMPLE_USER + " WITH PASSWORD '" + SAMPLE_PASSWORD + "'");
                log.info("Created sample user '{}'", SAMPLE_USER);
            }

            stmt.execute("GRANT CONNECT ON DATABASE " + SAMPLE_DB_NAME + " TO " + SAMPLE_USER);
        }

        try (Connection conn = DriverManager.getConnection(getSampleJdbcUrl(), getDbUsername(), getDbPassword())) {
            Statement stmt = conn.createStatement();
            stmt.execute("GRANT USAGE ON SCHEMA public TO " + SAMPLE_USER);
            stmt.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + SAMPLE_USER);
            stmt.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO " + SAMPLE_USER);
        }
    }

    /**
     * Recreates {@code inquery_sample} public schema when bundled seed version is newer than the DB.
     *
     * @return true if the sample database was recreated and reseeded (app catalog + vectors should be resynced)
     */
    private boolean seedTablesAndData() throws SQLException {
        try (Connection conn = DriverManager.getConnection(getSampleJdbcUrl(), getDbUsername(), getDbPassword())) {
            conn.setAutoCommit(false);

            int storedVersion = readSampleSeedVersion(conn);
            if (storedVersion >= SAMPLE_SEED_VERSION) {
                conn.commit();
                return false;
            }

            log.info("Sample DB seed version {} is below {}; recreating public schema and demo data",
                    storedVersion, SAMPLE_SEED_VERSION);

            Statement stmt = conn.createStatement();
            stmt.execute("DROP SCHEMA IF EXISTS public CASCADE");
            stmt.execute("CREATE SCHEMA public");
            applySamplePublicGrants(conn);

            stmt.execute("""
                CREATE TABLE customers (
                    id SERIAL PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    email VARCHAR(150) UNIQUE NOT NULL,
                    city VARCHAR(80),
                    country VARCHAR(60),
                    segment VARCHAR(30) DEFAULT 'Regular',
                    created_at TIMESTAMP DEFAULT NOW()
                )
            """);

            stmt.execute("""
                CREATE TABLE products (
                    id SERIAL PRIMARY KEY,
                    name VARCHAR(150) NOT NULL,
                    category VARCHAR(60) NOT NULL,
                    price NUMERIC(10,2) NOT NULL,
                    stock INTEGER DEFAULT 0,
                    is_active BOOLEAN DEFAULT TRUE,
                    created_at TIMESTAMP DEFAULT NOW()
                )
            """);

            stmt.execute("""
                CREATE TABLE orders (
                    id SERIAL PRIMARY KEY,
                    customer_id INTEGER REFERENCES customers(id),
                    order_date DATE NOT NULL,
                    total_amount NUMERIC(12,2) NOT NULL,
                    status VARCHAR(20) DEFAULT 'pending',
                    shipping_city VARCHAR(80),
                    created_at TIMESTAMP DEFAULT NOW()
                )
            """);

            stmt.execute("""
                CREATE TABLE order_items (
                    id SERIAL PRIMARY KEY,
                    order_id INTEGER REFERENCES orders(id),
                    product_id INTEGER REFERENCES products(id),
                    quantity INTEGER NOT NULL,
                    unit_price NUMERIC(10,2) NOT NULL
                )
            """);

            // Seed customers
            stmt.execute("""
                INSERT INTO customers (name, email, city, country, segment) VALUES
                ('Emma Johnson', 'emma.j@example.com', 'New York', 'USA', 'Premium'),
                ('Liam Smith', 'liam.s@example.com', 'London', 'UK', 'Regular'),
                ('Sophia Brown', 'sophia.b@example.com', 'Toronto', 'Canada', 'Premium'),
                ('Noah Wilson', 'noah.w@example.com', 'Sydney', 'Australia', 'Regular'),
                ('Olivia Davis', 'olivia.d@example.com', 'Berlin', 'Germany', 'VIP'),
                ('James Miller', 'james.m@example.com', 'Tokyo', 'Japan', 'Regular'),
                ('Ava Garcia', 'ava.g@example.com', 'Paris', 'France', 'Premium'),
                ('William Martinez', 'william.m@example.com', 'Seoul', 'South Korea', 'VIP'),
                ('Isabella Anderson', 'isabella.a@example.com', 'Singapore', 'Singapore', 'Premium'),
                ('Oliver Thomas', 'oliver.t@example.com', 'Amsterdam', 'Netherlands', 'Regular'),
                ('Mia Jackson', 'mia.j@example.com', 'San Francisco', 'USA', 'VIP'),
                ('Benjamin White', 'benjamin.w@example.com', 'Melbourne', 'Australia', 'Regular'),
                ('Charlotte Harris', 'charlotte.h@example.com', 'Vancouver', 'Canada', 'Premium'),
                ('Elijah Clark', 'elijah.c@example.com', 'Munich', 'Germany', 'Regular'),
                ('Amelia Lewis', 'amelia.l@example.com', 'Barcelona', 'Spain', 'Premium'),
                ('Lucas Robinson', 'lucas.r@example.com', 'Chicago', 'USA', 'Regular'),
                ('Harper Walker', 'harper.w@example.com', 'Dublin', 'Ireland', 'Regular'),
                ('Mason Hall', 'mason.h@example.com', 'Osaka', 'Japan', 'Premium'),
                ('Evelyn Allen', 'evelyn.a@example.com', 'Stockholm', 'Sweden', 'VIP'),
                ('Logan Young', 'logan.y@example.com', 'Zurich', 'Switzerland', 'Premium'),
                ('Aria King', 'aria.k@example.com', 'Los Angeles', 'USA', 'Regular'),
                ('Ethan Wright', 'ethan.w@example.com', 'Manchester', 'UK', 'Regular'),
                ('Scarlett Lopez', 'scarlett.l@example.com', 'Miami', 'USA', 'Premium'),
                ('Aiden Hill', 'aiden.h@example.com', 'Hong Kong', 'Hong Kong', 'VIP'),
                ('Grace Scott', 'grace.s@example.com', 'Copenhagen', 'Denmark', 'Regular'),
                ('Jackson Green', 'jackson.g@example.com', 'Boston', 'USA', 'Regular'),
                ('Chloe Adams', 'chloe.a@example.com', 'Lisbon', 'Portugal', 'Premium'),
                ('Sebastian Baker', 'sebastian.b@example.com', 'Vienna', 'Austria', 'Regular'),
                ('Lily Nelson', 'lily.n@example.com', 'Seattle', 'USA', 'Premium'),
                ('Henry Carter', 'henry.c@example.com', 'Prague', 'Czech Republic', 'Regular')
            """);

            // Seed products
            stmt.execute("""
                INSERT INTO products (name, category, price, stock) VALUES
                ('Wireless Headphones Pro', 'Electronics', 149.99, 250),
                ('Organic Cotton T-Shirt', 'Clothing', 34.99, 500),
                ('Smart Watch Series 5', 'Electronics', 299.99, 120),
                ('Running Shoes Ultra', 'Sports', 129.99, 300),
                ('Stainless Steel Water Bottle', 'Home', 24.99, 800),
                ('Laptop Stand Ergonomic', 'Electronics', 59.99, 400),
                ('Yoga Mat Premium', 'Sports', 45.99, 350),
                ('Coffee Maker Deluxe', 'Home', 89.99, 200),
                ('Bluetooth Speaker Mini', 'Electronics', 39.99, 600),
                ('Leather Wallet Classic', 'Accessories', 49.99, 450),
                ('Sunglasses Aviator', 'Accessories', 79.99, 300),
                ('Fitness Tracker Band', 'Electronics', 69.99, 350),
                ('Canvas Backpack', 'Accessories', 54.99, 400),
                ('Ceramic Mug Set', 'Home', 29.99, 700),
                ('Desk Lamp LED', 'Home', 44.99, 250),
                ('Winter Jacket Down', 'Clothing', 189.99, 150),
                ('Portable Charger 20000mAh', 'Electronics', 34.99, 500),
                ('Denim Jeans Slim', 'Clothing', 64.99, 400),
                ('Tennis Racket Pro', 'Sports', 159.99, 100),
                ('Essential Oil Diffuser', 'Home', 39.99, 300),
                ('Silk Scarf Designer', 'Accessories', 89.99, 200),
                ('Noise Cancelling Earbuds', 'Electronics', 199.99, 180),
                ('Hiking Boots Waterproof', 'Sports', 139.99, 220),
                ('Cast Iron Skillet', 'Home', 49.99, 350),
                ('Mechanical Keyboard RGB', 'Electronics', 119.99, 280)
            """);

            // Rolling window relative to DB CURRENT_DATE: orders from 5–365 days ago (~12 months) for demo analytics
            stmt.execute("""
                INSERT INTO orders (customer_id, order_date, total_amount, status, shipping_city) VALUES
                (1, CURRENT_DATE - INTERVAL '365 days', 184.98, 'delivered', 'New York'),
                (2, CURRENT_DATE - INTERVAL '340 days', 299.99, 'delivered', 'London'),
                (3, CURRENT_DATE - INTERVAL '335 days', 94.98, 'delivered', 'Toronto'),
                (5, CURRENT_DATE - INTERVAL '330 days', 149.99, 'delivered', 'Berlin'),
                (8, CURRENT_DATE - INTERVAL '320 days', 369.98, 'delivered', 'Seoul'),
                (11, CURRENT_DATE - INTERVAL '315 days', 259.98, 'delivered', 'San Francisco'),
                (1, CURRENT_DATE - INTERVAL '300 days', 129.99, 'delivered', 'New York'),
                (4, CURRENT_DATE - INTERVAL '290 days', 89.99, 'delivered', 'Sydney'),
                (7, CURRENT_DATE - INTERVAL '285 days', 199.98, 'delivered', 'Paris'),
                (9, CURRENT_DATE - INTERVAL '275 days', 449.98, 'delivered', 'Singapore'),
                (13, CURRENT_DATE - INTERVAL '270 days', 79.98, 'delivered', 'Vancouver'),
                (6, CURRENT_DATE - INTERVAL '260 days', 234.98, 'delivered', 'Tokyo'),
                (15, CURRENT_DATE - INTERVAL '250 days', 159.99, 'delivered', 'Barcelona'),
                (19, CURRENT_DATE - INTERVAL '245 days', 344.98, 'delivered', 'Stockholm'),
                (20, CURRENT_DATE - INTERVAL '240 days', 119.99, 'delivered', 'Zurich'),
                (2, CURRENT_DATE - INTERVAL '230 days', 164.98, 'delivered', 'London'),
                (10, CURRENT_DATE - INTERVAL '220 days', 89.99, 'delivered', 'Amsterdam'),
                (14, CURRENT_DATE - INTERVAL '210 days', 274.98, 'delivered', 'Munich'),
                (3, CURRENT_DATE - INTERVAL '200 days', 399.98, 'delivered', 'Toronto'),
                (24, CURRENT_DATE - INTERVAL '195 days', 529.97, 'delivered', 'Hong Kong'),
                (16, CURRENT_DATE - INTERVAL '185 days', 69.99, 'delivered', 'Chicago'),
                (21, CURRENT_DATE - INTERVAL '180 days', 184.98, 'delivered', 'Los Angeles'),
                (5, CURRENT_DATE - INTERVAL '170 days', 239.98, 'delivered', 'Berlin'),
                (12, CURRENT_DATE - INTERVAL '165 days', 109.98, 'delivered', 'Melbourne'),
                (8, CURRENT_DATE - INTERVAL '155 days', 299.99, 'delivered', 'Seoul'),
                (23, CURRENT_DATE - INTERVAL '150 days', 189.99, 'delivered', 'Miami'),
                (17, CURRENT_DATE - INTERVAL '140 days', 74.98, 'delivered', 'Dublin'),
                (1, CURRENT_DATE - INTERVAL '130 days', 319.98, 'delivered', 'New York'),
                (25, CURRENT_DATE - INTERVAL '125 days', 139.99, 'delivered', 'Copenhagen'),
                (9, CURRENT_DATE - INTERVAL '115 days', 199.99, 'delivered', 'Singapore'),
                (11, CURRENT_DATE - INTERVAL '110 days', 454.97, 'delivered', 'San Francisco'),
                (7, CURRENT_DATE - INTERVAL '100 days', 149.99, 'delivered', 'Paris'),
                (28, CURRENT_DATE - INTERVAL '95 days', 94.98, 'delivered', 'Vienna'),
                (30, CURRENT_DATE - INTERVAL '90 days', 179.98, 'delivered', 'Prague'),
                (22, CURRENT_DATE - INTERVAL '85 days', 264.98, 'delivered', 'Manchester'),
                (18, CURRENT_DATE - INTERVAL '80 days', 349.98, 'delivered', 'Osaka'),
                (4, CURRENT_DATE - INTERVAL '75 days', 69.99, 'delivered', 'Sydney'),
                (26, CURRENT_DATE - INTERVAL '70 days', 159.98, 'delivered', 'Boston'),
                (6, CURRENT_DATE - INTERVAL '60 days', 419.98, 'shipped', 'Tokyo'),
                (13, CURRENT_DATE - INTERVAL '55 days', 129.99, 'shipped', 'Vancouver'),
                (29, CURRENT_DATE - INTERVAL '50 days', 224.98, 'shipped', 'Seattle'),
                (19, CURRENT_DATE - INTERVAL '45 days', 89.99, 'shipped', 'Stockholm'),
                (3, CURRENT_DATE - INTERVAL '40 days', 279.98, 'shipped', 'Toronto'),
                (20, CURRENT_DATE - INTERVAL '35 days', 199.99, 'processing', 'Zurich'),
                (15, CURRENT_DATE - INTERVAL '30 days', 164.98, 'processing', 'Barcelona'),
                (8, CURRENT_DATE - INTERVAL '25 days', 449.97, 'processing', 'Seoul'),
                (24, CURRENT_DATE - INTERVAL '20 days', 309.98, 'processing', 'Hong Kong'),
                (1, CURRENT_DATE - INTERVAL '15 days', 179.98, 'pending', 'New York'),
                (11, CURRENT_DATE - INTERVAL '10 days', 399.98, 'pending', 'San Francisco'),
                (5, CURRENT_DATE - INTERVAL '5 days', 259.98, 'pending', 'Berlin')
            """);

            // Seed order_items
            stmt.execute("""
                INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES
                (1, 1, 1, 149.99), (1, 2, 1, 34.99),
                (2, 3, 1, 299.99),
                (3, 2, 1, 34.99), (3, 6, 1, 59.99),
                (4, 1, 1, 149.99),
                (5, 3, 1, 299.99), (5, 12, 1, 69.99),
                (6, 22, 1, 199.99), (6, 6, 1, 59.99),
                (7, 4, 1, 129.99),
                (8, 8, 1, 89.99),
                (9, 1, 1, 149.99), (9, 10, 1, 49.99),
                (10, 3, 1, 299.99), (10, 1, 1, 149.99),
                (11, 11, 1, 79.99),
                (12, 16, 1, 189.99), (12, 7, 1, 45.99),
                (13, 19, 1, 159.99),
                (14, 3, 1, 299.99), (14, 7, 1, 45.99),
                (15, 25, 1, 119.99),
                (16, 4, 1, 129.99), (16, 2, 1, 34.99),
                (17, 8, 1, 89.99),
                (18, 16, 1, 189.99), (18, 13, 1, 54.99), (18, 14, 1, 29.99),
                (19, 3, 1, 299.99), (19, 5, 2, 24.99), (19, 9, 1, 39.99),
                (20, 22, 2, 199.99), (20, 4, 1, 129.99),
                (21, 12, 1, 69.99),
                (22, 1, 1, 149.99), (22, 2, 1, 34.99),
                (23, 22, 1, 199.99), (23, 9, 1, 39.99),
                (24, 6, 1, 59.99), (24, 10, 1, 49.99),
                (25, 3, 1, 299.99),
                (26, 16, 1, 189.99),
                (27, 5, 1, 24.99), (27, 10, 1, 49.99),
                (28, 25, 1, 119.99), (28, 22, 1, 199.99),
                (29, 23, 1, 139.99),
                (30, 22, 1, 199.99),
                (31, 3, 1, 299.99), (31, 1, 1, 149.99),
                (32, 1, 1, 149.99),
                (33, 7, 1, 45.99), (33, 14, 1, 29.99), (33, 5, 1, 24.99),
                (34, 4, 1, 129.99), (34, 10, 1, 49.99),
                (35, 18, 2, 64.99), (35, 2, 2, 34.99), (35, 17, 1, 34.99),
                (36, 16, 1, 189.99), (36, 19, 1, 159.99),
                (37, 12, 1, 69.99),
                (38, 6, 1, 59.99), (38, 15, 1, 44.99), (38, 14, 1, 29.99),
                (39, 3, 1, 299.99), (39, 25, 1, 119.99),
                (40, 4, 1, 129.99),
                (41, 22, 1, 199.99), (41, 5, 1, 24.99),
                (42, 8, 1, 89.99),
                (43, 1, 1, 149.99), (43, 4, 1, 129.99),
                (44, 22, 1, 199.99),
                (45, 2, 2, 34.99), (45, 17, 2, 34.99), (45, 9, 1, 39.99),
                (46, 3, 1, 299.99), (46, 1, 1, 149.99),
                (47, 16, 1, 189.99), (47, 25, 1, 119.99),
                (48, 4, 1, 129.99), (48, 10, 1, 49.99),
                (49, 3, 1, 299.99), (49, 15, 1, 44.99), (49, 6, 1, 59.99),
                (50, 22, 1, 199.99), (50, 6, 1, 59.99)
            """);

            // --- Additional source tables for richer ERD ---

            stmt.execute("""
                CREATE TABLE product_reviews (
                    id SERIAL PRIMARY KEY,
                    customer_id INTEGER REFERENCES customers(id),
                    product_id INTEGER REFERENCES products(id),
                    rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
                    review_text TEXT,
                    created_at TIMESTAMP DEFAULT NOW()
                )
            """);

            stmt.execute("""
                CREATE TABLE shipping (
                    id SERIAL PRIMARY KEY,
                    order_id INTEGER REFERENCES orders(id),
                    carrier VARCHAR(60) NOT NULL,
                    tracking_number VARCHAR(100),
                    shipped_at TIMESTAMP,
                    delivered_at TIMESTAMP,
                    status VARCHAR(30) DEFAULT 'preparing'
                )
            """);

            // Seed product_reviews
            stmt.execute("""
                INSERT INTO product_reviews (customer_id, product_id, rating, review_text, created_at) VALUES
                (1, 1, 5, 'Amazing sound quality, best headphones I have ever owned!', CURRENT_DATE - INTERVAL '340 days'),
                (2, 3, 4, 'Great smartwatch but battery could be better.', CURRENT_DATE - INTERVAL '330 days'),
                (3, 2, 5, 'Super soft fabric, fits perfectly.', CURRENT_DATE - INTERVAL '325 days'),
                (5, 1, 4, 'Good noise cancellation, comfortable for long use.', CURRENT_DATE - INTERVAL '320 days'),
                (8, 3, 5, 'Love the health tracking features!', CURRENT_DATE - INTERVAL '310 days'),
                (11, 22, 5, 'Best earbuds on the market, period.', CURRENT_DATE - INTERVAL '305 days'),
                (1, 4, 4, 'Very comfortable for running, good arch support.', CURRENT_DATE - INTERVAL '290 days'),
                (4, 8, 3, 'Decent coffee maker but slow brewing.', CURRENT_DATE - INTERVAL '280 days'),
                (7, 1, 5, 'Second pair, gifting to my friend!', CURRENT_DATE - INTERVAL '275 days'),
                (9, 3, 4, 'Sleek design and accurate GPS.', CURRENT_DATE - INTERVAL '265 days'),
                (13, 11, 4, 'Stylish sunglasses, UV protection works well.', CURRENT_DATE - INTERVAL '260 days'),
                (6, 16, 5, 'Kept me warm in -10C weather!', CURRENT_DATE - INTERVAL '250 days'),
                (15, 19, 4, 'Good racket for intermediate players.', CURRENT_DATE - INTERVAL '240 days'),
                (19, 3, 5, 'Best smartwatch purchase ever.', CURRENT_DATE - INTERVAL '235 days'),
                (20, 25, 4, 'Mechanical keyboard feels amazing to type on.', CURRENT_DATE - INTERVAL '230 days'),
                (2, 4, 3, 'Shoes are okay but sizing runs small.', CURRENT_DATE - INTERVAL '220 days'),
                (10, 8, 4, 'Makes great espresso!', CURRENT_DATE - INTERVAL '210 days'),
                (14, 16, 5, 'Premium quality jacket, worth every penny.', CURRENT_DATE - INTERVAL '200 days'),
                (3, 3, 5, 'Upgraded from Series 3, huge improvement.', CURRENT_DATE - INTERVAL '190 days'),
                (24, 22, 5, 'Incredible sound isolation.', CURRENT_DATE - INTERVAL '185 days'),
                (16, 12, 3, 'Battery life is mediocre.', CURRENT_DATE - INTERVAL '175 days'),
                (21, 1, 4, 'Comfortable but a bit heavy.', CURRENT_DATE - INTERVAL '170 days'),
                (5, 22, 5, 'Perfect for commuting.', CURRENT_DATE - INTERVAL '160 days'),
                (12, 6, 4, 'Sturdy laptop stand, improved my posture.', CURRENT_DATE - INTERVAL '155 days'),
                (8, 3, 5, 'Buying another for my wife!', CURRENT_DATE - INTERVAL '145 days'),
                (23, 16, 4, 'Warm and stylish winter jacket.', CURRENT_DATE - INTERVAL '140 days'),
                (17, 5, 5, 'Keeps water cold for 24 hours.', CURRENT_DATE - INTERVAL '130 days'),
                (1, 25, 4, 'RGB lighting is beautiful.', CURRENT_DATE - INTERVAL '120 days'),
                (25, 23, 5, 'Best hiking boots, waterproof as advertised.', CURRENT_DATE - INTERVAL '115 days'),
                (9, 22, 4, 'Great ANC but touch controls are finicky.', CURRENT_DATE - INTERVAL '105 days'),
                (11, 3, 5, 'Third smartwatch from this brand, never disappointed.', CURRENT_DATE - INTERVAL '100 days'),
                (7, 1, 5, 'Still my favorite headphones after 6 months.', CURRENT_DATE - INTERVAL '90 days'),
                (28, 7, 4, 'Thick yoga mat, very comfortable.', CURRENT_DATE - INTERVAL '85 days'),
                (30, 4, 4, 'Good for daily running.', CURRENT_DATE - INTERVAL '80 days'),
                (22, 18, 3, 'Jeans quality is average for the price.', CURRENT_DATE - INTERVAL '75 days'),
                (18, 16, 5, 'Best jacket I own.', CURRENT_DATE - INTERVAL '70 days'),
                (4, 12, 4, 'Accurate step counting.', CURRENT_DATE - INTERVAL '65 days'),
                (26, 6, 5, 'Elevated my desk setup.', CURRENT_DATE - INTERVAL '55 days'),
                (6, 3, 4, 'Excellent for fitness tracking.', CURRENT_DATE - INTERVAL '45 days'),
                (29, 22, 5, 'Crystal clear audio quality.', CURRENT_DATE - INTERVAL '35 days')
            """);

            // Seed shipping
            stmt.execute("""
                INSERT INTO shipping (order_id, carrier, tracking_number, shipped_at, delivered_at, status) VALUES
                (1, 'FedEx', 'FX100200300', CURRENT_DATE - INTERVAL '363 days', CURRENT_DATE - INTERVAL '360 days', 'delivered'),
                (2, 'DHL', 'DHL200300400', CURRENT_DATE - INTERVAL '338 days', CURRENT_DATE - INTERVAL '333 days', 'delivered'),
                (3, 'UPS', 'UPS300400500', CURRENT_DATE - INTERVAL '333 days', CURRENT_DATE - INTERVAL '329 days', 'delivered'),
                (4, 'DHL', 'DHL400500600', CURRENT_DATE - INTERVAL '328 days', CURRENT_DATE - INTERVAL '324 days', 'delivered'),
                (5, 'FedEx', 'FX500600700', CURRENT_DATE - INTERVAL '318 days', CURRENT_DATE - INTERVAL '314 days', 'delivered'),
                (6, 'UPS', 'UPS600700800', CURRENT_DATE - INTERVAL '313 days', CURRENT_DATE - INTERVAL '309 days', 'delivered'),
                (7, 'FedEx', 'FX700800900', CURRENT_DATE - INTERVAL '298 days', CURRENT_DATE - INTERVAL '295 days', 'delivered'),
                (8, 'DHL', 'DHL800900100', CURRENT_DATE - INTERVAL '288 days', CURRENT_DATE - INTERVAL '284 days', 'delivered'),
                (9, 'UPS', 'UPS900100200', CURRENT_DATE - INTERVAL '283 days', CURRENT_DATE - INTERVAL '278 days', 'delivered'),
                (10, 'FedEx', 'FX101112131', CURRENT_DATE - INTERVAL '273 days', CURRENT_DATE - INTERVAL '269 days', 'delivered'),
                (20, 'DHL', 'DHL202122232', CURRENT_DATE - INTERVAL '193 days', CURRENT_DATE - INTERVAL '188 days', 'delivered'),
                (25, 'FedEx', 'FX252627282', CURRENT_DATE - INTERVAL '153 days', CURRENT_DATE - INTERVAL '149 days', 'delivered'),
                (28, 'UPS', 'UPS282930313', CURRENT_DATE - INTERVAL '128 days', CURRENT_DATE - INTERVAL '124 days', 'delivered'),
                (31, 'FedEx', 'FX313233343', CURRENT_DATE - INTERVAL '108 days', CURRENT_DATE - INTERVAL '104 days', 'delivered'),
                (35, 'DHL', 'DHL353637383', CURRENT_DATE - INTERVAL '83 days', CURRENT_DATE - INTERVAL '79 days', 'delivered'),
                (39, 'FedEx', 'FX394041424', CURRENT_DATE - INTERVAL '58 days', CURRENT_DATE - INTERVAL '54 days', 'shipped'),
                (40, 'UPS', 'UPS404142434', CURRENT_DATE - INTERVAL '53 days', NULL, 'shipped'),
                (41, 'DHL', 'DHL414243444', CURRENT_DATE - INTERVAL '48 days', NULL, 'shipped'),
                (42, 'FedEx', 'FX424344454', CURRENT_DATE - INTERVAL '43 days', NULL, 'shipped'),
                (43, 'UPS', 'UPS434445464', CURRENT_DATE - INTERVAL '38 days', NULL, 'shipped'),
                (44, 'DHL', NULL, NULL, NULL, 'preparing'),
                (45, 'FedEx', NULL, NULL, NULL, 'preparing'),
                (46, 'UPS', NULL, NULL, NULL, 'preparing'),
                (47, 'DHL', NULL, NULL, NULL, 'preparing')
            """);

            // --- Derived aggregate tables (FACT_AGG) for lineage demos ---

            stmt.execute("""
                CREATE TABLE monthly_revenue AS
                SELECT
                    DATE_TRUNC('month', order_date)::DATE AS month,
                    COUNT(*) AS total_orders,
                    SUM(total_amount) AS revenue,
                    AVG(total_amount) AS avg_order_value,
                    COUNT(DISTINCT customer_id) AS unique_customers
                FROM orders
                GROUP BY DATE_TRUNC('month', order_date)
                ORDER BY month
            """);

            stmt.execute("""
                CREATE TABLE customer_lifetime_value AS
                SELECT
                    c.id AS customer_id,
                    c.name,
                    c.segment,
                    c.country,
                    COUNT(o.id) AS total_orders,
                    COALESCE(SUM(o.total_amount), 0) AS lifetime_value,
                    COALESCE(AVG(o.total_amount), 0) AS avg_order_value,
                    MIN(o.order_date) AS first_order_date,
                    MAX(o.order_date) AS last_order_date,
                    MAX(o.order_date) - MIN(o.order_date) AS customer_tenure_days
                FROM customers c
                LEFT JOIN orders o ON c.id = o.customer_id
                GROUP BY c.id, c.name, c.segment, c.country
            """);

            stmt.execute("""
                CREATE TABLE product_sales_ranking AS
                SELECT
                    p.id AS product_id,
                    p.name AS product_name,
                    p.category,
                    p.price AS current_price,
                    COUNT(DISTINCT oi.order_id) AS times_ordered,
                    SUM(oi.quantity) AS total_units_sold,
                    SUM(oi.quantity * oi.unit_price) AS total_revenue,
                    AVG(pr.rating) AS avg_rating,
                    COUNT(pr.id) AS review_count
                FROM products p
                LEFT JOIN order_items oi ON p.id = oi.product_id
                LEFT JOIN product_reviews pr ON p.id = pr.product_id
                GROUP BY p.id, p.name, p.category, p.price
                ORDER BY total_revenue DESC NULLS LAST
            """);

            stmt.execute("""
                CREATE TABLE category_sales_summary AS
                SELECT
                    p.category,
                    COUNT(DISTINCT p.id) AS product_count,
                    SUM(oi.quantity) AS total_units_sold,
                    SUM(oi.quantity * oi.unit_price) AS total_revenue,
                    AVG(oi.unit_price) AS avg_unit_price,
                    COUNT(DISTINCT oi.order_id) AS total_orders
                FROM products p
                LEFT JOIN order_items oi ON p.id = oi.product_id
                GROUP BY p.category
                ORDER BY total_revenue DESC NULLS LAST
            """);

            // Add table comments for AI context
            stmt.execute("COMMENT ON TABLE customers IS 'Store customers with segmentation (Regular, Premium, VIP)'");
            stmt.execute("COMMENT ON TABLE products IS 'Product catalog with categories and pricing'");
            stmt.execute("COMMENT ON TABLE orders IS 'Customer orders with status tracking (pending, processing, shipped, delivered)'");
            stmt.execute("COMMENT ON TABLE order_items IS 'Individual line items within each order'");
            stmt.execute("COMMENT ON TABLE product_reviews IS 'Customer reviews and ratings for purchased products (1-5 stars)'");
            stmt.execute("COMMENT ON TABLE shipping IS 'Shipping and delivery tracking per order (FedEx, DHL, UPS)'");
            stmt.execute("COMMENT ON TABLE monthly_revenue IS '[TYPE=FACT_AGG] Monthly aggregated revenue metrics derived from orders'");
            stmt.execute("COMMENT ON TABLE customer_lifetime_value IS '[TYPE=FACT_AGG] Customer lifetime value analysis derived from customers + orders'");
            stmt.execute("COMMENT ON TABLE product_sales_ranking IS '[TYPE=FACT_AGG] Product performance ranking derived from products + order_items + reviews'");
            stmt.execute("COMMENT ON TABLE category_sales_summary IS '[TYPE=FACT_AGG] Category-level sales aggregation derived from products + order_items'");

            stmt.execute("COMMENT ON COLUMN orders.status IS 'Order status: pending, processing, shipped, delivered'");
            stmt.execute("COMMENT ON COLUMN customers.segment IS 'Customer tier: Regular, Premium, VIP'");
            stmt.execute("COMMENT ON COLUMN shipping.status IS 'Shipping status: preparing, shipped, delivered'");
            stmt.execute("COMMENT ON COLUMN product_reviews.rating IS 'Star rating from 1 (worst) to 5 (best)'");

            stmt.execute("CREATE SCHEMA IF NOT EXISTS inquery_sample_meta");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS inquery_sample_meta.seed_version (
                    id INT PRIMARY KEY DEFAULT 1,
                    version INT NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT inquery_sample_seed_single_row CHECK (id = 1)
                )
                """);
            stmt.execute("INSERT INTO inquery_sample_meta.seed_version (id, version) VALUES (1, " + SAMPLE_SEED_VERSION + ") "
                    + "ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version, updated_at = CURRENT_TIMESTAMP");
            stmt.execute("REVOKE ALL ON SCHEMA inquery_sample_meta FROM " + SAMPLE_USER);

            applySamplePublicGrants(conn);

            conn.commit();
            log.info("Sample database seeded with e-commerce data (10 tables), seed_version={}", SAMPLE_SEED_VERSION);
            return true;
        }
    }

    private int readSampleSeedVersion(Connection conn) {
        Savepoint savepoint = null;
        try {
            savepoint = conn.setSavepoint("sample_seed_version_read");
        } catch (SQLException e) {
            log.debug("Could not create savepoint for sample seed version read: {}", e.getMessage());
        }

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT version FROM inquery_sample_meta.seed_version WHERE id = 1")) {
            if (rs.next()) {
                releaseSavepoint(conn, savepoint);
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            rollbackToSavepoint(conn, savepoint);
            log.debug("Sample seed version not readable (first run or pre-version DB): {}", e.getMessage());
        }
        return 0;
    }

    private void rollbackToSavepoint(Connection conn, Savepoint savepoint) {
        if (savepoint == null) {
            return;
        }
        try {
            conn.rollback(savepoint);
        } catch (SQLException e) {
            log.debug("Could not rollback sample seed version savepoint: {}", e.getMessage());
        }
    }

    private void releaseSavepoint(Connection conn, Savepoint savepoint) {
        if (savepoint == null) {
            return;
        }
        try {
            conn.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            log.debug("Could not release sample seed version savepoint: {}", e.getMessage());
        }
    }

    private void applySamplePublicGrants(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("GRANT USAGE ON SCHEMA public TO " + SAMPLE_USER);
            stmt.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + SAMPLE_USER);
            stmt.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO " + SAMPLE_USER);
        }
    }

    /**
     * Removes catalog, lineage, and sample pgvector rows so classpath seeds can re-apply cleanly.
     */
    private void deleteSampleAppCatalogLineageAndVectors(Long dataSourceId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM data_catalog_column WHERE table_id IN (SELECT id FROM data_catalog_table WHERE data_source_id = ?)")) {
                ps.setLong(1, dataSourceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM data_catalog_table WHERE data_source_id = ?")) {
                ps.setLong(1, dataSourceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM table_lineage WHERE data_source_id = ?")) {
                ps.setLong(1, dataSourceId);
                ps.executeUpdate();
            }
            conn.commit();

            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM vector_embeddings WHERE namespace = ? AND id = ANY (?)")) {
                ps.setString(1, sampleVectorNamespace());
                ps.setArray(2, conn.createArrayOf("text", sampleVectorIds().toArray(new String[0])));
                ps.executeUpdate();
            }
            conn.commit();
            log.info("Cleared sample catalog, lineage, and vectors for dataSourceId={}", dataSourceId);
        } catch (Exception e) {
            log.warn("Failed to clear sample app metadata for dataSourceId={}: {}", dataSourceId, e.getMessage());
        }
    }

    @EventListener
    public void createSampleConnectionForNewUser(UserCreatedEvent event) {
        if (event != null && event.userId() != null) {
            createSampleConnectionForUser(event.userId());
        }
    }

    private void createSampleConnectionsForExistingUsers() {
        List<Long> userIds = new ArrayList<>();
        try {
            Dbutils.setSession();
            InqueryUserMapper userMapper = Dbutils.getMapper(InqueryUserMapper.class);
            List<InqueryUserDO> users = userMapper.selectList(null);
            for (InqueryUserDO user : users) {
                if (shouldCreateSampleForUser(user)) {
                    userIds.add(user.getId());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list users for sample connection backfill: {}", e.getMessage());
        } finally {
            Dbutils.removeSession();
        }

        for (Long userId : userIds) {
            createSampleConnectionForUser(userId);
        }
    }

    private boolean shouldCreateSampleForUser(InqueryUserDO user) {
        return user != null
                && user.getId() != null
                && !RoleCodeEnum.DESKTOP.getDefaultUserId().equals(user.getId())
                && !RoleCodeEnum.DESKTOP.getCode().equals(user.getRoleCode());
    }

    /**
     * Creates a sample database connection (data_source row) for a user.
     * Skips if one already exists.
     */
    public void createSampleConnectionForUser(Long userId) {
        try {
            Dbutils.setSession();
            DataSourceMapper mapper = Dbutils.getMapper(DataSourceMapper.class);

            LambdaQueryWrapper<DataSourceDO> query = new LambdaQueryWrapper<>();
            query.eq(DataSourceDO::getUserId, userId)
                    .eq(DataSourceDO::getAlias, SAMPLE_ALIAS);
            DataSourceDO existing = mapper.selectOne(query);
            if (existing != null) {
                boolean needsRepair = existing.getDriverConfig() == null
                        || existing.getDriverConfig().isBlank()
                        || !SAMPLE_USER.equals(existing.getUserName());
                if (needsRepair) {
                    repairExistingRecord(mapper, existing);
                }
                return;
            }

            String appUrl = env.getProperty("spring.datasource.url", "jdbc:postgresql://localhost:15432/inquery_desktop");
            String host = "localhost";
            String port = "15432";
            try {
                java.net.URI uri = new java.net.URI(appUrl.replace("jdbc:", ""));
                if (uri.getHost() != null) host = uri.getHost();
                if (uri.getPort() > 0) port = String.valueOf(uri.getPort());
            } catch (Exception ignored) {}

            String encryptedPassword;
            try {
                DesUtil desUtil = new DesUtil(DesUtil.DES_KEY);
                encryptedPassword = desUtil.encrypt(SAMPLE_PASSWORD, "CBC");
            } catch (Exception e) {
                log.warn("Failed to encrypt sample DB password: {}", e.getMessage());
                encryptedPassword = SAMPLE_PASSWORD;
            }

            String driverConfigJson = "{\"jdbcDriver\":\"postgresql-42.5.1.jar\","
                    + "\"jdbcDriverClass\":\"org.postgresql.Driver\","
                    + "\"dbType\":\"POSTGRESQL\","
                    + "\"custom\":false,"
                    + "\"defaultDriver\":true}";

            DataSourceDO ds = new DataSourceDO();
            ds.setAlias(SAMPLE_ALIAS);
            ds.setType("POSTGRESQL");
            ds.setHost(host);
            ds.setPort(port);
            ds.setUrl(getSampleJdbcUrl());
            ds.setUserName(SAMPLE_USER);
            ds.setPassword(encryptedPassword);
            ds.setDriverConfig(driverConfigJson);
            ds.setUserId(userId);
            ds.setKind("PRIVATE");
            ds.setGmtCreate(DateUtil.date());
            ds.setGmtModified(DateUtil.date());

            mapper.insert(ds);
            log.info("Created sample connection for userId={}, dataSourceId={}", userId, ds.getId());
            seedCatalogForConnection(ds.getId(), userId);
            seedVectorEmbeddings(ds.getId(), false);
        } catch (Exception e) {
            log.warn("Failed to create sample connection for userId={}: {}", userId, e.getMessage());
        } finally {
            Dbutils.removeSession();
        }
    }

    /**
     * Reads sample-catalog-seed.sql from classpath, replaces placeholders, and executes
     * against the app database to pre-fill data catalog entries for the sample connection.
     */
    private void seedCatalogForConnection(Long dataSourceId, Long userId) {
        try {
            ClassPathResource resource = new ClassPathResource("sample-catalog-seed.sql");
            String sql;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                sql = reader.lines().collect(Collectors.joining("\n"));
            }

            sql = sql.replace("${DATA_SOURCE_ID}", String.valueOf(dataSourceId))
                     .replace("${USER_ID}", String.valueOf(userId));

            String cleaned = sql.lines()
                    .filter(line -> !line.trim().startsWith("--"))
                    .collect(Collectors.joining("\n"));

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                Statement stmt = conn.createStatement();
                for (String statement : cleaned.split(";")) {
                    String trimmed = statement.trim();
                    if (!trimmed.isEmpty()) {
                        stmt.execute(trimmed);
                    }
                }
                conn.commit();
            }
            log.info("Seeded catalog data for dataSourceId={}, userId={}", dataSourceId, userId);
        } catch (Exception e) {
            log.warn("Failed to seed catalog for dataSourceId={}: {}", dataSourceId, e.getMessage());
        }
    }

    /**
     * Generates vector embeddings for all sample tables using the local ONNX model
     * and stores them in the pgvector table. When {@code forceReseed} is false, skips if enough rows already exist.
     */
    private void seedVectorEmbeddings(Long dataSourceId, boolean forceReseed) {
        String namespace = sampleVectorNamespace();
        try {
            try (Connection conn = dataSource.getConnection()) {
                conn.createStatement().execute("CREATE EXTENSION IF NOT EXISTS vector");
                conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS vector_embeddings (" +
                    "  id VARCHAR(512) PRIMARY KEY," +
                    "  embedding vector(384)," +
                    "  namespace VARCHAR(128) NOT NULL DEFAULT 'default'," +
                    "  metadata JSONB," +
                    "  active BOOLEAN DEFAULT TRUE," +
                    "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");

                if (!forceReseed) {
                    ResultSet rs = conn.createStatement().executeQuery(
                            "SELECT COUNT(*) FROM vector_embeddings WHERE namespace = '" + namespace +
                                    "' AND id = ANY (" + sampleVectorIdSqlArray() + ")");
                    rs.next();
                    long existingCount = rs.getLong(1);
                    rs.close();
                    if (existingCount >= 10) {
                        log.info("Vector embeddings already exist for namespace={} (count={}), skipping", namespace, existingCount);
                        return;
                    }
                }
            }

            ClassPathResource resource = new ClassPathResource("sample-vector-schemas.txt");
            String content;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                content = reader.lines().collect(Collectors.joining("\n"));
            }

            Pattern pattern = Pattern.compile("---TABLE_START: (\\w+)---\\n(.*?)\\n---TABLE_END---", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(content);
            Map<String, String> tableSchemas = new LinkedHashMap<>();
            while (matcher.find()) {
                tableSchemas.put(matcher.group(1), matcher.group(2).trim());
            }

            if (tableSchemas.isEmpty()) {
                log.warn("No table schemas found in sample-vector-schemas.txt");
                return;
            }

            log.info("Generating ONNX embeddings for {} sample tables...", tableSchemas.size());
            EmbeddingModel embeddingModel = new AllMiniLmL6V2QuantizedEmbeddingModel();

            String upsertSql = "INSERT INTO vector_embeddings (id, embedding, namespace, metadata, active, updated_at) " +
                "VALUES (?, ?::vector, ?, ?::jsonb, true, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (id) DO UPDATE SET namespace = EXCLUDED.namespace, embedding = EXCLUDED.embedding, " +
                "metadata = EXCLUDED.metadata, active = EXCLUDED.active, updated_at = CURRENT_TIMESTAMP";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(upsertSql)) {

                for (Map.Entry<String, String> entry : tableSchemas.entrySet()) {
                    String tableName = entry.getKey();
                    String schemaText = entry.getValue();

                    Response<Embedding> response = embeddingModel.embed(schemaText);
                    float[] vector = response.content().vector();

                    StringBuilder vecLiteral = new StringBuilder("[");
                    for (int i = 0; i < vector.length; i++) {
                        if (i > 0) vecLiteral.append(",");
                        vecLiteral.append(vector[i]);
                    }
                    vecLiteral.append("]");

                    String id = GatewayClientService.generateTableVectorId(SAMPLE_DB_NAME, "public", tableName);
                    String metadata = String.format(
                        "{\"dataSourceId\":\"%d\",\"tableName\":\"%s\",\"schemaName\":\"public\",\"databaseName\":\"inquery_sample\",\"dbType\":\"POSTGRESQL\",\"active\":\"true\",\"tableSchema\":%s}",
                        dataSourceId, tableName, escapeJsonString(schemaText));

                    ps.setString(1, id);
                    ps.setString(2, vecLiteral.toString());
                    ps.setString(3, namespace);
                    ps.setString(4, metadata);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            log.info("Seeded {} vector embeddings for namespace={}", tableSchemas.size(), namespace);
        } catch (Exception e) {
            log.warn("Failed to seed vector embeddings for dataSourceId={}: {}", dataSourceId, e.getMessage());
        }
    }

    private static String sampleVectorNamespace() {
        return "postgresql";
    }

    private static List<String> sampleVectorIds() {
        return List.of(
                "customers",
                "products",
                "orders",
                "order_items",
                "product_reviews",
                "shipping",
                "monthly_revenue",
                "customer_lifetime_value",
                "product_sales_ranking",
                "category_sales_summary"
        ).stream()
                .map(table -> GatewayClientService.generateTableVectorId(SAMPLE_DB_NAME, "public", table))
                .collect(Collectors.toList());
    }

    private static String sampleVectorIdSqlArray() {
        return sampleVectorIds().stream()
                .map(id -> "'" + id.replace("'", "''") + "'")
                .collect(Collectors.joining(",", "ARRAY[", "]::text[]"));
    }

    private static String escapeJsonString(String text) {
        return "\"" + text.replace("\\", "\\\\")
                         .replace("\"", "\\\"")
                         .replace("\n", "\\n")
                         .replace("\r", "\\r")
                         .replace("\t", "\\t") + "\"";
    }

    private void repairExistingRecord(DataSourceMapper mapper, DataSourceDO existing) {
        try {
            String encryptedPassword;
            try {
                DesUtil desUtil = new DesUtil(DesUtil.DES_KEY);
                encryptedPassword = desUtil.encrypt(SAMPLE_PASSWORD, "CBC");
            } catch (Exception e) {
                encryptedPassword = SAMPLE_PASSWORD;
            }

            String driverConfigJson = "{\"jdbcDriver\":\"postgresql-42.5.1.jar\","
                    + "\"jdbcDriverClass\":\"org.postgresql.Driver\","
                    + "\"dbType\":\"POSTGRESQL\","
                    + "\"custom\":false,"
                    + "\"defaultDriver\":true}";

            existing.setUserName(SAMPLE_USER);
            existing.setPassword(encryptedPassword);
            existing.setDriverConfig(driverConfigJson);
            existing.setUrl(getSampleJdbcUrl());
            existing.setGmtModified(DateUtil.date());
            mapper.updateById(existing);
            log.info("Repaired sample connection id={} with dedicated user and driver config", existing.getId());
        } catch (Exception e) {
            log.warn("Failed to repair sample connection: {}", e.getMessage());
        }
    }
}
