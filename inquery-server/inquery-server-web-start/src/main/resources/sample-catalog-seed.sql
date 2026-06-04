-- Sample E-Commerce DB - Data Catalog + Lineage Seed
-- Placeholders: ${DATA_SOURCE_ID}, ${USER_ID}
-- 10 tables: 6 source + 4 mart

-- ============================================================
-- TABLE CATALOG: customers
-- ============================================================
INSERT INTO data_catalog_table (data_source_id, database_name, schema_name, table_name, table_description, user_id, active, gmt_create, gmt_modified)
VALUES (${DATA_SOURCE_ID}, 'inquery_sample', 'public', 'customers',
        'Customer master table containing profile and segmentation data. Each row represents a unique registered customer with their contact info, location, and loyalty tier (Regular, Premium, VIP).',
        ${USER_ID}, 1, NOW(), NOW())
ON CONFLICT (data_source_id, database_name, schema_name, table_name) DO NOTHING;

INSERT INTO data_catalog_column (table_id, column_name, column_description, schema_info, example_values, ordinal_position, gmt_create, gmt_modified)
SELECT t.id, v.column_name, v.column_description, v.schema_info, v.example_values, v.ordinal_position, NOW(), NOW()
FROM data_catalog_table t
CROSS JOIN (VALUES
    ('id',         'Unique customer identifier (auto-increment PK)',           '{"type":"integer","nullable":false}', '["1","2","3"]',                                     1),
    ('name',       'Full name of the customer',                                '{"type":"varchar(100)","nullable":false}', '["Emma Johnson","Liam Smith","Sophia Brown"]',  2),
    ('email',      'Unique email address used for login and communication',    '{"type":"varchar(150)","nullable":false}', '["emma.j@example.com","liam.s@example.com"]',   3),
    ('city',       'City where the customer is located',                       '{"type":"varchar(80)","nullable":true}', '["New York","London","Toronto","Seoul"]',           4),
    ('country',    'Country where the customer resides',                       '{"type":"varchar(60)","nullable":true}', '["USA","UK","Canada","South Korea"]',               5),
    ('segment',    'Customer loyalty tier: Regular, Premium, or VIP',          '{"type":"varchar(30)","nullable":true}', '["Regular","Premium","VIP"]',                      6),
    ('created_at', 'Timestamp when the customer record was created',           '{"type":"timestamp","nullable":true}', '["2025-01-15 10:30:00"]',                            7)
) AS v(column_name, column_description, schema_info, example_values, ordinal_position)
WHERE t.data_source_id = ${DATA_SOURCE_ID} AND t.table_name = 'customers'
AND NOT EXISTS (SELECT 1 FROM data_catalog_column c WHERE c.table_id = t.id AND c.column_name = v.column_name);


-- ============================================================
-- TABLE CATALOG: products
-- ============================================================
INSERT INTO data_catalog_table (data_source_id, database_name, schema_name, table_name, table_description, user_id, active, gmt_create, gmt_modified)
VALUES (${DATA_SOURCE_ID}, 'inquery_sample', 'public', 'products',
        'Product catalog containing all items available for purchase. Includes pricing, inventory stock levels, and category classification (Electronics, Clothing, Sports, Home, Accessories).',
        ${USER_ID}, 1, NOW(), NOW())
ON CONFLICT (data_source_id, database_name, schema_name, table_name) DO NOTHING;

INSERT INTO data_catalog_column (table_id, column_name, column_description, schema_info, example_values, ordinal_position, gmt_create, gmt_modified)
SELECT t.id, v.column_name, v.column_description, v.schema_info, v.example_values, v.ordinal_position, NOW(), NOW()
FROM data_catalog_table t
CROSS JOIN (VALUES
    ('id',         'Unique product identifier (auto-increment PK)',            '{"type":"integer","nullable":false}', '["1","2","3"]',                                             1),
    ('name',       'Product display name',                                     '{"type":"varchar(150)","nullable":false}', '["Wireless Headphones Pro","Smart Watch Series 5"]',   2),
    ('category',   'Product category: Electronics, Clothing, Sports, Home, Accessories', '{"type":"varchar(60)","nullable":false}', '["Electronics","Clothing","Sports","Home","Accessories"]', 3),
    ('price',      'Unit price in USD',                                        '{"type":"numeric(10,2)","nullable":false}', '["149.99","34.99","299.99"]',                        4),
    ('stock',      'Current available inventory count',                        '{"type":"integer","nullable":true}', '["250","500","120"]',                                        5),
    ('is_active',  'Whether the product is currently available for sale',       '{"type":"boolean","nullable":true}', '["true"]',                                                  6),
    ('created_at', 'Timestamp when the product was added to catalog',          '{"type":"timestamp","nullable":true}', '["2025-01-10 09:00:00"]',                                  7)
) AS v(column_name, column_description, schema_info, example_values, ordinal_position)
WHERE t.data_source_id = ${DATA_SOURCE_ID} AND t.table_name = 'products'
AND NOT EXISTS (SELECT 1 FROM data_catalog_column c WHERE c.table_id = t.id AND c.column_name = v.column_name);


-- ============================================================
-- TABLE CATALOG: orders
-- ============================================================
INSERT INTO data_catalog_table (data_source_id, database_name, schema_name, table_name, table_description, user_id, active, gmt_create, gmt_modified)
VALUES (${DATA_SOURCE_ID}, 'inquery_sample', 'public', 'orders',
        'Order transactions placed by customers. Tracks order lifecycle status (pending -> processing -> shipped -> delivered), total amount, and shipping destination. Links to customers via customer_id FK.',
        ${USER_ID}, 1, NOW(), NOW())
ON CONFLICT (data_source_id, database_name, schema_name, table_name) DO NOTHING;

INSERT INTO data_catalog_column (table_id, column_name, column_description, schema_info, example_values, ordinal_position, gmt_create, gmt_modified)
SELECT t.id, v.column_name, v.column_description, v.schema_info, v.example_values, v.ordinal_position, NOW(), NOW()
FROM data_catalog_table t
CROSS JOIN (VALUES
    ('id',            'Unique order identifier (auto-increment PK)',                    '{"type":"integer","nullable":false}', '["1","2","3"]',                                           1),
    ('customer_id',   'FK to customers.id - the customer who placed this order',        '{"type":"integer","nullable":true}', '["1","5","11"]',                                           2),
    ('order_date',    'Date when the order was placed',                                  '{"type":"date","nullable":false}', '["2025-03-15","2025-06-20","2025-12-01"]',                  3),
    ('total_amount',  'Total monetary value of the order in USD',                        '{"type":"numeric(12,2)","nullable":false}', '["184.98","299.99","449.98"]',                     4),
    ('status',        'Order lifecycle status: pending, processing, shipped, delivered',  '{"type":"varchar(20)","nullable":true}', '["pending","processing","shipped","delivered"]',      5),
    ('shipping_city', 'City where the order is being shipped to',                        '{"type":"varchar(80)","nullable":true}', '["New York","Seoul","London"]',                        6),
    ('created_at',    'Timestamp when the order record was created',                     '{"type":"timestamp","nullable":true}', '["2025-03-15 14:30:00"]',                                7)
) AS v(column_name, column_description, schema_info, example_values, ordinal_position)
WHERE t.data_source_id = ${DATA_SOURCE_ID} AND t.table_name = 'orders'
AND NOT EXISTS (SELECT 1 FROM data_catalog_column c WHERE c.table_id = t.id AND c.column_name = v.column_name);


-- ============================================================
-- TABLE CATALOG: order_items
-- ============================================================
INSERT INTO data_catalog_table (data_source_id, database_name, schema_name, table_name, table_description, user_id, active, gmt_create, gmt_modified)
VALUES (${DATA_SOURCE_ID}, 'inquery_sample', 'public', 'order_items',
        'Line items within each order. Each row represents a single product in an order with its quantity and unit price at time of purchase. Links to orders via order_id FK and products via product_id FK.',
        ${USER_ID}, 1, NOW(), NOW())
ON CONFLICT (data_source_id, database_name, schema_name, table_name) DO NOTHING;

INSERT INTO data_catalog_column (table_id, column_name, column_description, schema_info, example_values, ordinal_position, gmt_create, gmt_modified)
SELECT t.id, v.column_name, v.column_description, v.schema_info, v.example_values, v.ordinal_position, NOW(), NOW()
FROM data_catalog_table t
CROSS JOIN (VALUES
    ('id',         'Unique line item identifier (auto-increment PK)',               '{"type":"integer","nullable":false}', '["1","2","3"]',                  1),
    ('order_id',   'FK to orders.id - which order this item belongs to',            '{"type":"integer","nullable":true}', '["1","2","5"]',                   2),
    ('product_id', 'FK to products.id - which product was purchased',               '{"type":"integer","nullable":true}', '["1","3","22"]',                  3),
    ('quantity',   'Number of units purchased for this line item',                   '{"type":"integer","nullable":false}', '["1","2","3"]',                  4),
    ('unit_price', 'Price per unit at time of purchase (may differ from current product price)', '{"type":"numeric(10,2)","nullable":false}', '["149.99","34.99","199.99"]', 5)
) AS v(column_name, column_description, schema_info, example_values, ordinal_position)
WHERE t.data_source_id = ${DATA_SOURCE_ID} AND t.table_name = 'order_items'
AND NOT EXISTS (SELECT 1 FROM data_catalog_column c WHERE c.table_id = t.id AND c.column_name = v.column_name);


-- ============================================================
-- TABLE CATALOG: product_reviews
-- ============================================================
INSERT INTO data_catalog_table (data_source_id, database_name, schema_name, table_name, table_description, user_id, active, gmt_create, gmt_modified)
VALUES (${DATA_SOURCE_ID}, 'inquery_sample', 'public', 'product_reviews',
        'Customer reviews and ratings for purchased products. Each row is a 1-5 star review with optional text, linking customers to products they have reviewed.',
        ${USER_ID}, 1, NOW(), NOW())
ON CONFLICT (data_source_id, database_name, schema_name, table_name) DO NOTHING;

INSERT INTO data_catalog_column (table_id, column_name, column_description, schema_info, example_values, ordinal_position, gmt_create, gmt_modified)
SELECT t.id, v.column_name, v.column_description, v.schema_info, v.example_values, v.ordinal_position, NOW(), NOW()
FROM data_catalog_table t
CROSS JOIN (VALUES
    ('id',          'Unique review identifier (auto-increment PK)',            '{"type":"integer","nullable":false}', '["1","2","3"]',                                                      1),
    ('customer_id', 'FK to customers.id - reviewer',                           '{"type":"integer","nullable":true}', '["1","5","11"]',                                                      2),
    ('product_id',  'FK to products.id - reviewed product',                    '{"type":"integer","nullable":true}', '["1","3","22"]',                                                      3),
    ('rating',      'Star rating from 1 (worst) to 5 (best)',                  '{"type":"integer","nullable":false}', '["3","4","5"]',                                                      4),
    ('review_text', 'Free-text review content written by the customer',        '{"type":"text","nullable":true}', '["Amazing sound quality!","Great but battery could be better."]',          5),
    ('created_at',  'Timestamp when the review was submitted',                 '{"type":"timestamp","nullable":true}', '["2025-02-10 09:30:00"]',                                           6)
) AS v(column_name, column_description, schema_info, example_values, ordinal_position)
WHERE t.data_source_id = ${DATA_SOURCE_ID} AND t.table_name = 'product_reviews'
AND NOT EXISTS (SELECT 1 FROM data_catalog_column c WHERE c.table_id = t.id AND c.column_name = v.column_name);


-- ============================================================
-- TABLE CATALOG: shipping
-- ============================================================
INSERT INTO data_catalog_table (data_source_id, database_name, schema_name, table_name, table_description, user_id, active, gmt_create, gmt_modified)
VALUES (${DATA_SOURCE_ID}, 'inquery_sample', 'public', 'shipping',
        'Shipping and delivery tracking per order. Records carrier (FedEx, DHL, UPS), tracking number, and timestamps for shipment and delivery. Status: preparing, shipped, delivered.',
        ${USER_ID}, 1, NOW(), NOW())
ON CONFLICT (data_source_id, database_name, schema_name, table_name) DO NOTHING;

INSERT INTO data_catalog_column (table_id, column_name, column_description, schema_info, example_values, ordinal_position, gmt_create, gmt_modified)
SELECT t.id, v.column_name, v.column_description, v.schema_info, v.example_values, v.ordinal_position, NOW(), NOW()
FROM data_catalog_table t
CROSS JOIN (VALUES
    ('id',              'Unique shipping record identifier (auto-increment PK)',     '{"type":"integer","nullable":false}', '["1","2","3"]',                     1),
    ('order_id',        'FK to orders.id - the order being shipped',                '{"type":"integer","nullable":true}', '["1","5","10"]',                     2),
    ('carrier',         'Shipping carrier company name',                            '{"type":"varchar(60)","nullable":false}', '["FedEx","DHL","UPS"]',          3),
    ('tracking_number', 'Carrier tracking number for shipment lookup',              '{"type":"varchar(100)","nullable":true}', '["FX100200300","DHL200300400"]', 4),
    ('shipped_at',      'Timestamp when the package was handed to carrier',         '{"type":"timestamp","nullable":true}', '["2025-04-20 14:00:00"]',          5),
    ('delivered_at',    'Timestamp when the package was delivered to customer',      '{"type":"timestamp","nullable":true}', '["2025-04-23 10:30:00"]',          6),
    ('status',          'Shipping status: preparing, shipped, delivered',            '{"type":"varchar(30)","nullable":true}', '["preparing","shipped","delivered"]', 7)
) AS v(column_name, column_description, schema_info, example_values, ordinal_position)
WHERE t.data_source_id = ${DATA_SOURCE_ID} AND t.table_name = 'shipping'
AND NOT EXISTS (SELECT 1 FROM data_catalog_column c WHERE c.table_id = t.id AND c.column_name = v.column_name);


-- ============================================================
-- TABLE CATALOG: monthly_revenue (FACT_AGG derived)
-- ============================================================
INSERT INTO data_catalog_table (data_source_id, database_name, schema_name, table_name, table_description, user_id, active, gmt_create, gmt_modified)
VALUES (${DATA_SOURCE_ID}, 'inquery_sample', 'public', 'monthly_revenue',
        '[TYPE=FACT_AGG] Monthly aggregated revenue metrics. Summarizes total orders, revenue, average order value, and unique customer count per month. Derived from: orders.',
        ${USER_ID}, 1, NOW(), NOW())
ON CONFLICT (data_source_id, database_name, schema_name, table_name) DO NOTHING;

INSERT INTO data_catalog_column (table_id, column_name, column_description, schema_info, example_values, ordinal_position, gmt_create, gmt_modified)
SELECT t.id, v.column_name, v.column_description, v.schema_info, v.example_values, v.ordinal_position, NOW(), NOW()
FROM data_catalog_table t
CROSS JOIN (VALUES
    ('month',            'First day of the aggregated month',                  '{"type":"date","nullable":true}', '["2025-01-01","2025-06-01","2025-12-01"]',    1),
    ('total_orders',     'Number of orders placed in this month',              '{"type":"bigint","nullable":true}', '["5","8","12"]',                            2),
    ('revenue',          'Sum of all order total_amount for the month (USD)',   '{"type":"numeric","nullable":true}', '["1249.93","2850.00","4320.50"]',          3),
    ('avg_order_value',  'Average order value for the month (USD)',             '{"type":"numeric","nullable":true}', '["249.99","356.25","360.04"]',             4),
    ('unique_customers', 'Count of distinct customers who ordered this month',  '{"type":"bigint","nullable":true}', '["4","7","10"]',                           5)
) AS v(column_name, column_description, schema_info, example_values, ordinal_position)
WHERE t.data_source_id = ${DATA_SOURCE_ID} AND t.table_name = 'monthly_revenue'
AND NOT EXISTS (SELECT 1 FROM data_catalog_column c WHERE c.table_id = t.id AND c.column_name = v.column_name);


-- ============================================================
-- TABLE CATALOG: customer_lifetime_value (FACT_AGG derived)
-- ============================================================
INSERT INTO data_catalog_table (data_source_id, database_name, schema_name, table_name, table_description, user_id, active, gmt_create, gmt_modified)
VALUES (${DATA_SOURCE_ID}, 'inquery_sample', 'public', 'customer_lifetime_value',
        '[TYPE=FACT_AGG] Customer lifetime value (CLV) analysis. Aggregates each customer total orders, lifetime spend, average order value, and tenure. Derived from: customers + orders.',
        ${USER_ID}, 1, NOW(), NOW())
ON CONFLICT (data_source_id, database_name, schema_name, table_name) DO NOTHING;

INSERT INTO data_catalog_column (table_id, column_name, column_description, schema_info, example_values, ordinal_position, gmt_create, gmt_modified)
SELECT t.id, v.column_name, v.column_description, v.schema_info, v.example_values, v.ordinal_position, NOW(), NOW()
FROM data_catalog_table t
CROSS JOIN (VALUES
    ('customer_id',          'FK to customers.id',                                              '{"type":"integer","nullable":true}', '["1","5","11"]',                       1),
    ('name',                 'Customer full name (denormalized from customers)',                 '{"type":"varchar(100)","nullable":true}', '["Emma Johnson","Olivia Davis"]',  2),
    ('segment',              'Customer tier (denormalized from customers)',                      '{"type":"varchar(30)","nullable":true}', '["Regular","Premium","VIP"]',       3),
    ('country',              'Customer country (denormalized from customers)',                   '{"type":"varchar(60)","nullable":true}', '["USA","UK","Germany"]',            4),
    ('total_orders',         'Total number of orders placed by this customer',                   '{"type":"bigint","nullable":true}', '["3","5","8"]',                        5),
    ('lifetime_value',       'Total spend across all orders (USD)',                               '{"type":"numeric","nullable":true}', '["684.95","1249.93","2150.00"]',     6),
    ('avg_order_value',      'Average order value for this customer (USD)',                       '{"type":"numeric","nullable":true}', '["228.32","249.99","268.75"]',       7),
    ('first_order_date',     'Date of the customer first order',                                 '{"type":"date","nullable":true}', '["2025-01-15","2025-03-20"]',           8),
    ('last_order_date',      'Date of the customer most recent order',                           '{"type":"date","nullable":true}', '["2025-11-20","2026-01-15"]',           9),
    ('customer_tenure_days', 'Days between first and last order (customer lifespan)',             '{"type":"integer","nullable":true}', '["120","250","335"]',               10)
) AS v(column_name, column_description, schema_info, example_values, ordinal_position)
WHERE t.data_source_id = ${DATA_SOURCE_ID} AND t.table_name = 'customer_lifetime_value'
AND NOT EXISTS (SELECT 1 FROM data_catalog_column c WHERE c.table_id = t.id AND c.column_name = v.column_name);


-- ============================================================
-- TABLE CATALOG: product_sales_ranking (FACT_AGG derived)
-- ============================================================
INSERT INTO data_catalog_table (data_source_id, database_name, schema_name, table_name, table_description, user_id, active, gmt_create, gmt_modified)
VALUES (${DATA_SOURCE_ID}, 'inquery_sample', 'public', 'product_sales_ranking',
        '[TYPE=FACT_AGG] Product performance ranking by total revenue. Includes units sold, order count, average rating, and review count. Derived from: products + order_items + product_reviews.',
        ${USER_ID}, 1, NOW(), NOW())
ON CONFLICT (data_source_id, database_name, schema_name, table_name) DO NOTHING;

INSERT INTO data_catalog_column (table_id, column_name, column_description, schema_info, example_values, ordinal_position, gmt_create, gmt_modified)
SELECT t.id, v.column_name, v.column_description, v.schema_info, v.example_values, v.ordinal_position, NOW(), NOW()
FROM data_catalog_table t
CROSS JOIN (VALUES
    ('product_id',       'FK to products.id',                                            '{"type":"integer","nullable":true}', '["1","3","22"]',                              1),
    ('product_name',     'Product display name (denormalized from products)',             '{"type":"varchar(150)","nullable":true}', '["Wireless Headphones Pro","Smart Watch Series 5"]', 2),
    ('category',         'Product category (denormalized from products)',                 '{"type":"varchar(60)","nullable":true}', '["Electronics","Clothing","Sports"]',        3),
    ('current_price',    'Current catalog price (denormalized from products)',            '{"type":"numeric(10,2)","nullable":true}', '["149.99","299.99"]',                     4),
    ('times_ordered',    'Number of distinct orders containing this product',             '{"type":"bigint","nullable":true}', '["8","12","15"]',                               5),
    ('total_units_sold', 'Total quantity sold across all orders',                          '{"type":"bigint","nullable":true}', '["10","15","22"]',                             6),
    ('total_revenue',    'Total revenue generated by this product (USD)',                  '{"type":"numeric","nullable":true}', '["1499.90","4499.85"]',                      7),
    ('avg_rating',       'Average customer review rating (1-5 scale)',                     '{"type":"numeric","nullable":true}', '["4.2","4.8","3.5"]',                        8),
    ('review_count',     'Total number of reviews received',                               '{"type":"bigint","nullable":true}', '["3","7","12"]',                             9)
) AS v(column_name, column_description, schema_info, example_values, ordinal_position)
WHERE t.data_source_id = ${DATA_SOURCE_ID} AND t.table_name = 'product_sales_ranking'
AND NOT EXISTS (SELECT 1 FROM data_catalog_column c WHERE c.table_id = t.id AND c.column_name = v.column_name);


-- ============================================================
-- TABLE CATALOG: category_sales_summary (FACT_AGG derived)
-- ============================================================
INSERT INTO data_catalog_table (data_source_id, database_name, schema_name, table_name, table_description, user_id, active, gmt_create, gmt_modified)
VALUES (${DATA_SOURCE_ID}, 'inquery_sample', 'public', 'category_sales_summary',
        '[TYPE=FACT_AGG] Category-level sales aggregation. Summarizes product count, total units sold, revenue, average unit price, and order count per category. Derived from: products + order_items.',
        ${USER_ID}, 1, NOW(), NOW())
ON CONFLICT (data_source_id, database_name, schema_name, table_name) DO NOTHING;

INSERT INTO data_catalog_column (table_id, column_name, column_description, schema_info, example_values, ordinal_position, gmt_create, gmt_modified)
SELECT t.id, v.column_name, v.column_description, v.schema_info, v.example_values, v.ordinal_position, NOW(), NOW()
FROM data_catalog_table t
CROSS JOIN (VALUES
    ('category',         'Product category name',                                       '{"type":"varchar(60)","nullable":true}', '["Electronics","Clothing","Sports","Home","Accessories"]', 1),
    ('product_count',    'Number of distinct products in this category',                 '{"type":"bigint","nullable":true}', '["8","3","4","6","4"]',                                       2),
    ('total_units_sold', 'Total quantity of items sold in this category',                 '{"type":"bigint","nullable":true}', '["45","12","8","20","10"]',                                  3),
    ('total_revenue',    'Total revenue generated by this category (USD)',                '{"type":"numeric","nullable":true}', '["8500.50","1200.00","3400.00"]',                           4),
    ('avg_unit_price',   'Average unit price across all sold items in this category',     '{"type":"numeric","nullable":true}', '["188.88","78.32","118.99"]',                               5),
    ('total_orders',     'Number of distinct orders containing products from this category', '{"type":"bigint","nullable":true}', '["30","10","8"]',                                       6)
) AS v(column_name, column_description, schema_info, example_values, ordinal_position)
WHERE t.data_source_id = ${DATA_SOURCE_ID} AND t.table_name = 'category_sales_summary'
AND NOT EXISTS (SELECT 1 FROM data_catalog_column c WHERE c.table_id = t.id AND c.column_name = v.column_name);


-- ============================================================
-- LINEAGE: derived aggregate tables <- source tables
-- ============================================================

INSERT INTO table_lineage (data_source_id, database_name, schema_name, table_name, source_query, source_tables, description, user_id, gmt_create, gmt_modified)
SELECT ${DATA_SOURCE_ID}, v.database_name, v.schema_name, v.table_name, v.source_query, v.source_tables, v.description, ${USER_ID}, NOW(), NOW()
FROM (VALUES
    ('inquery_sample', 'public', 'monthly_revenue',
     'SELECT DATE_TRUNC(''month'', order_date)::DATE AS month, COUNT(*) AS total_orders, SUM(total_amount) AS revenue, AVG(total_amount) AS avg_order_value, COUNT(DISTINCT customer_id) AS unique_customers FROM orders GROUP BY DATE_TRUNC(''month'', order_date) ORDER BY month',
     'inquery_sample.public.orders',
     'Monthly aggregated revenue metrics from orders table'),

    ('inquery_sample', 'public', 'customer_lifetime_value',
     'SELECT c.id AS customer_id, c.name, c.segment, c.country, COUNT(o.id) AS total_orders, COALESCE(SUM(o.total_amount), 0) AS lifetime_value, COALESCE(AVG(o.total_amount), 0) AS avg_order_value, MIN(o.order_date) AS first_order_date, MAX(o.order_date) AS last_order_date, MAX(o.order_date) - MIN(o.order_date) AS customer_tenure_days FROM customers c LEFT JOIN orders o ON c.id = o.customer_id GROUP BY c.id, c.name, c.segment, c.country',
     'inquery_sample.public.customers,inquery_sample.public.orders',
     'Customer lifetime value analysis joining customers with their orders'),

    ('inquery_sample', 'public', 'product_sales_ranking',
     'SELECT p.id AS product_id, p.name AS product_name, p.category, p.price AS current_price, COUNT(DISTINCT oi.order_id) AS times_ordered, SUM(oi.quantity) AS total_units_sold, SUM(oi.quantity * oi.unit_price) AS total_revenue, AVG(pr.rating) AS avg_rating, COUNT(pr.id) AS review_count FROM products p LEFT JOIN order_items oi ON p.id = oi.product_id LEFT JOIN product_reviews pr ON p.id = pr.product_id GROUP BY p.id, p.name, p.category, p.price ORDER BY total_revenue DESC NULLS LAST',
     'inquery_sample.public.products,inquery_sample.public.order_items,inquery_sample.public.product_reviews',
     'Product performance ranking combining sales data with review ratings'),

    ('inquery_sample', 'public', 'category_sales_summary',
     'SELECT p.category, COUNT(DISTINCT p.id) AS product_count, SUM(oi.quantity) AS total_units_sold, SUM(oi.quantity * oi.unit_price) AS total_revenue, AVG(oi.unit_price) AS avg_unit_price, COUNT(DISTINCT oi.order_id) AS total_orders FROM products p LEFT JOIN order_items oi ON p.id = oi.product_id GROUP BY p.category ORDER BY total_revenue DESC NULLS LAST',
     'inquery_sample.public.products,inquery_sample.public.order_items',
     'Category-level sales aggregation from products and order items')
) AS v(database_name, schema_name, table_name, source_query, source_tables, description)
WHERE NOT EXISTS (
    SELECT 1 FROM table_lineage tl
    WHERE tl.data_source_id = ${DATA_SOURCE_ID}
    AND tl.table_name = v.table_name
    AND tl.schema_name = v.schema_name
);
