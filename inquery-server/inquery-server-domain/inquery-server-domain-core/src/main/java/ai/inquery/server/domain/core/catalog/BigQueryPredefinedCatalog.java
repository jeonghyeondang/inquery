package ai.inquery.server.domain.core.catalog;

import ai.inquery.server.domain.core.catalog.PredefinedTableMetadata.PredefinedColumnMetadata;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Predefined metadata catalog for well-known BigQuery tables.
 *
 * Google services (GA4, Firebase, Google Ads, etc.) automatically create tables
 * in BigQuery with publicly documented schemas. Column descriptions and custom-value
 * examples are provided from this catalog; table descriptions are AI-generated on Collect.
 *
 * Detection strategy:
 * 1. Fixed dataset name prefix: analytics_*, firebase_crashlytics, firebase_messaging, etc.
 * 2. Table name pattern: for user-configured datasets (Google Ads, Cloud Logging, etc.)
 */
public class BigQueryPredefinedCatalog {

    // Dataset prefix patterns (fixed by Google)
    private static final Map<String, String> DATASET_PREFIX_SERVICE_MAP = new LinkedHashMap<>();
    static {
        DATASET_PREFIX_SERVICE_MAP.put("analytics_", "GA4");
        DATASET_PREFIX_SERVICE_MAP.put("firebase_crashlytics", "FIREBASE_CRASHLYTICS");
        DATASET_PREFIX_SERVICE_MAP.put("firebase_messaging", "FIREBASE_CLOUD_MESSAGING");
        DATASET_PREFIX_SERVICE_MAP.put("firebase_performance", "FIREBASE_PERFORMANCE");
        DATASET_PREFIX_SERVICE_MAP.put("searchconsole", "GOOGLE_SEARCH_CONSOLE");
    }

    // Table name patterns for user-configured datasets
    private static final List<TablePatternEntry> TABLE_NAME_PATTERNS = new ArrayList<>();
    static {
        // Google Ads
        TABLE_NAME_PATTERNS.add(new TablePatternEntry(Pattern.compile("^p?_?Campaign_\\d+$"), "GOOGLE_ADS"));
        TABLE_NAME_PATTERNS.add(new TablePatternEntry(Pattern.compile("^p?_?AdGroup(Ad|BasicStats|ConversionStats)?_\\d+$"), "GOOGLE_ADS"));
        TABLE_NAME_PATTERNS.add(new TablePatternEntry(Pattern.compile("^p?_?Keyword(BasicStats)?_\\d+$"), "GOOGLE_ADS"));
        TABLE_NAME_PATTERNS.add(new TablePatternEntry(Pattern.compile("^p?_?AccountBasicStats_\\d+$"), "GOOGLE_ADS"));
        TABLE_NAME_PATTERNS.add(new TablePatternEntry(Pattern.compile("^p?_?Customer_\\d+$"), "GOOGLE_ADS"));

        // Cloud Logging
        TABLE_NAME_PATTERNS.add(new TablePatternEntry(Pattern.compile("^cloudaudit_googleapis_com_.*"), "CLOUD_LOGGING"));
        TABLE_NAME_PATTERNS.add(new TablePatternEntry(Pattern.compile("^syslog_\\d{8}$"), "CLOUD_LOGGING"));
        TABLE_NAME_PATTERNS.add(new TablePatternEntry(Pattern.compile("^requests_\\d{8}$"), "CLOUD_LOGGING"));

        // Cloud Billing
        TABLE_NAME_PATTERNS.add(new TablePatternEntry(Pattern.compile("^gcp_billing_export_(resource_)?v1_.*"), "CLOUD_BILLING"));
        TABLE_NAME_PATTERNS.add(new TablePatternEntry(Pattern.compile("^cloud_pricing_export$"), "CLOUD_BILLING"));

        // Google Workspace
        TABLE_NAME_PATTERNS.add(new TablePatternEntry(Pattern.compile("^activity_\\d{8}$"), "GOOGLE_WORKSPACE"));
        TABLE_NAME_PATTERNS.add(new TablePatternEntry(Pattern.compile("^usage_\\d{8}$"), "GOOGLE_WORKSPACE"));
    }

    // Registry of predefined metadata by service + table type
    private static final Map<String, PredefinedTableMetadata> METADATA_REGISTRY = new HashMap<>();
    static {
        registerGA4Metadata();
        registerFirebaseCrashlyticsMetadata();
        registerFirebaseCloudMessagingMetadata();
        registerFirebasePerformanceMetadata();
        registerGoogleSearchConsoleMetadata();
        registerCloudBillingMetadata();
        registerCloudLoggingMetadata();
        registerGoogleWorkspaceMetadata();
    }

    /**
     * Detect if a dataset/table combination is a well-known Google service table.
     *
     * @param datasetName BigQuery dataset name
     * @param tableName   BigQuery table name
     * @return service identifier (e.g., "GA4") or null if not recognized
     */
    public static String detectService(String datasetName, String tableName) {
        if (datasetName == null || tableName == null) {
            return null;
        }

        // 1. Check dataset prefix
        String lowerDataset = datasetName.toLowerCase();
        for (Map.Entry<String, String> entry : DATASET_PREFIX_SERVICE_MAP.entrySet()) {
            if (lowerDataset.startsWith(entry.getKey()) || lowerDataset.equals(entry.getKey())) {
                return entry.getValue();
            }
        }

        // 2. Check table name patterns
        for (TablePatternEntry pattern : TABLE_NAME_PATTERNS) {
            if (pattern.pattern.matcher(tableName).matches()) {
                return pattern.service;
            }
        }

        return null;
    }

    /**
     * Get predefined metadata for a detected Google service table.
     *
     * @param service   service identifier from detectService()
     * @param tableName table name (used to determine specific table type within service)
     * @return predefined metadata or null if not available
     */
    public static PredefinedTableMetadata getMetadata(String service, String tableName) {
        if (service == null || tableName == null) {
            return null;
        }

        String tableType = resolveTableType(service, tableName);
        if (tableType == null) {
            return null;
        }

        String key = service + ":" + tableType;
        return METADATA_REGISTRY.get(key);
    }

    /**
     * Check if predefined metadata is available for the given dataset/table.
     */
    public static boolean hasPredefinedMetadata(String datasetName, String tableName) {
        String service = detectService(datasetName, tableName);
        if (service == null) return false;
        return getMetadata(service, tableName) != null;
    }

    /**
     * Definitions for well-known GA4 {@code event_name} values (automatically collected,
     * enhanced measurement and recommended events). These are stable, documented by Google,
     * so we can attach an accurate per-event meaning without calling the AI.
     *
     * <p>Used to build a rich {@code event_name} column description listing what each event
     * that actually exists in the dataset represents, so the SQL-generation AI understands
     * the semantics of each value it can filter on.
     */
    private static final Map<String, String> GA4_EVENT_DEFINITIONS = new LinkedHashMap<>();
    static {
        // Automatically collected events
        GA4_EVENT_DEFINITIONS.put("first_visit", "User's first visit to a website (web) - automatically collected");
        GA4_EVENT_DEFINITIONS.put("first_open", "User opens the app for the first time after install/reinstall");
        GA4_EVENT_DEFINITIONS.put("session_start", "A new session begins");
        GA4_EVENT_DEFINITIONS.put("user_engagement", "App/site is in the foreground or page is in focus for a measurable time");
        GA4_EVENT_DEFINITIONS.put("app_remove", "App package is removed/uninstalled from an Android device");
        GA4_EVENT_DEFINITIONS.put("app_update", "App is updated to a new version and relaunched");
        GA4_EVENT_DEFINITIONS.put("app_clear_data", "User resets/clears the app data or uninstalls the app");
        GA4_EVENT_DEFINITIONS.put("app_exception", "App crashes or throws an exception");
        GA4_EVENT_DEFINITIONS.put("os_update", "Device operating system is updated to a new version");
        GA4_EVENT_DEFINITIONS.put("in_app_purchase", "User completes an in-app purchase (store-reported)");
        GA4_EVENT_DEFINITIONS.put("ad_impression", "An ad impression is shown (apps with AdMob/ad SDK)");
        GA4_EVENT_DEFINITIONS.put("ad_click", "User clicks an ad");
        GA4_EVENT_DEFINITIONS.put("notification_receive", "Device receives a push notification while app is in background");
        GA4_EVENT_DEFINITIONS.put("notification_open", "User opens a push notification");
        GA4_EVENT_DEFINITIONS.put("notification_dismiss", "User dismisses a push notification");
        GA4_EVENT_DEFINITIONS.put("notification_foreground", "Notification received while app is in the foreground");
        GA4_EVENT_DEFINITIONS.put("screen_view", "User views a screen (app)");
        // Enhanced measurement events (web)
        GA4_EVENT_DEFINITIONS.put("page_view", "A page is loaded or the browser history state changes (web pageview)");
        GA4_EVENT_DEFINITIONS.put("scroll", "User scrolls to the bottom (90%) of a page");
        GA4_EVENT_DEFINITIONS.put("click", "User clicks a link leading away from the current domain (outbound click)");
        GA4_EVENT_DEFINITIONS.put("view_search_results", "User performs a site search (search results page viewed)");
        GA4_EVENT_DEFINITIONS.put("video_start", "Video begins to play (embedded YouTube)");
        GA4_EVENT_DEFINITIONS.put("video_progress", "Video progresses past 10/25/50/75% of its duration");
        GA4_EVENT_DEFINITIONS.put("video_complete", "Video reaches its end");
        GA4_EVENT_DEFINITIONS.put("file_download", "User clicks a link to download a file (pdf, doc, zip, etc.)");
        GA4_EVENT_DEFINITIONS.put("form_start", "User first interacts with a form in a session");
        GA4_EVENT_DEFINITIONS.put("form_submit", "User submits a form");
        // Recommended ecommerce events
        GA4_EVENT_DEFINITIONS.put("view_item", "User views the details of a product/item");
        GA4_EVENT_DEFINITIONS.put("view_item_list", "User views a list of products/items");
        GA4_EVENT_DEFINITIONS.put("select_item", "User selects an item from a list");
        GA4_EVENT_DEFINITIONS.put("add_to_cart", "User adds an item to the shopping cart");
        GA4_EVENT_DEFINITIONS.put("remove_from_cart", "User removes an item from the shopping cart");
        GA4_EVENT_DEFINITIONS.put("view_cart", "User views the shopping cart");
        GA4_EVENT_DEFINITIONS.put("add_to_wishlist", "User adds an item to a wishlist");
        GA4_EVENT_DEFINITIONS.put("begin_checkout", "User starts the checkout process");
        GA4_EVENT_DEFINITIONS.put("add_shipping_info", "User submits shipping information during checkout");
        GA4_EVENT_DEFINITIONS.put("add_payment_info", "User submits payment information during checkout");
        GA4_EVENT_DEFINITIONS.put("purchase", "User completes a purchase (revenue/transaction)");
        GA4_EVENT_DEFINITIONS.put("refund", "A purchase is refunded (full or partial)");
        GA4_EVENT_DEFINITIONS.put("view_promotion", "User views a promotion");
        GA4_EVENT_DEFINITIONS.put("select_promotion", "User selects/clicks a promotion");
        // Recommended general / engagement events
        GA4_EVENT_DEFINITIONS.put("generate_lead", "A lead is captured (e.g., contact form submitted)");
        GA4_EVENT_DEFINITIONS.put("login", "User logs in");
        GA4_EVENT_DEFINITIONS.put("sign_up", "User signs up / creates an account");
        GA4_EVENT_DEFINITIONS.put("search", "User searches content within the app/site");
        GA4_EVENT_DEFINITIONS.put("share", "User shares content");
        GA4_EVENT_DEFINITIONS.put("select_content", "User selects a piece of content");
        GA4_EVENT_DEFINITIONS.put("tutorial_begin", "User begins a tutorial/onboarding flow");
        GA4_EVENT_DEFINITIONS.put("tutorial_complete", "User completes a tutorial/onboarding flow");
        // Recommended games events
        GA4_EVENT_DEFINITIONS.put("earn_virtual_currency", "User earns virtual currency (in-game)");
        GA4_EVENT_DEFINITIONS.put("spend_virtual_currency", "User spends virtual currency (in-game)");
        GA4_EVENT_DEFINITIONS.put("level_start", "User starts a game level");
        GA4_EVENT_DEFINITIONS.put("level_end", "User finishes a game level");
        GA4_EVENT_DEFINITIONS.put("level_up", "User levels up in a game");
        GA4_EVENT_DEFINITIONS.put("post_score", "User posts a score");
        GA4_EVENT_DEFINITIONS.put("unlock_achievement", "User unlocks an achievement");
        GA4_EVENT_DEFINITIONS.put("join_group", "User joins a group/guild");
    }

    /**
     * Whether a collected {@code event_name} value is worth keeping in catalog metadata.
     * Filters out SQL nulls, blank strings, and the literal {@code "null"} token sometimes
     * stored in raw exports — those are data-quality gaps, not real event definitions.
     */
    public static boolean isValidGa4EventName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim();
        return !trimmed.isEmpty() && !"null".equalsIgnoreCase(trimmed);
    }

    /**
     * Build an enriched description for the GA4 {@code event_name} column that explains the
     * meaning of each event value actually present in the dataset. Known GA4 events get their
     * documented definition; unrecognised values are flagged as custom events so the AI knows
     * they are implementation-specific.
     *
     * @param presentEventNames distinct event_name values collected from the table (may be null/empty)
     * @return a multi-line description, or {@code null} if no event names were provided
     */
    public static String buildGa4EventNameDescription(Collection<String> presentEventNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("GA4 event name. Identifies the type of user interaction that produced the row. ");
        sb.append("Each event has its own set of event_params and semantics.");

        if (presentEventNames == null || presentEventNames.isEmpty()) {
            return sb.toString();
        }

        // De-duplicate while preserving the provided order; skip null/blank/literal "null"
        Set<String> seen = new LinkedHashSet<>();
        for (String name : presentEventNames) {
            if (isValidGa4EventName(name)) {
                seen.add(name.trim());
            }
        }
        if (seen.isEmpty()) {
            return sb.toString();
        }
        sb.append("\nEvents present in this dataset:");
        for (String name : seen) {
            String def = GA4_EVENT_DEFINITIONS.get(name);
            sb.append("\n- ").append(name).append(": ");
            sb.append(def != null ? def : "Custom (implementation-specific) event");
        }
        return sb.toString();
    }

    private static final String GA4_EVENT_NAME_FILTER =
        "event_name IS NOT NULL AND TRIM(event_name) != '' AND LOWER(TRIM(event_name)) != 'null'";

    /**
     * SQL to collect distinct GA4 {@code event_name} values from recent shards.
     * Handles daily ({@code events_YYYYMMDD}), intraday ({@code events_intraday_YYYYMMDD}),
     * and fresh ({@code events_fresh_YYYYMMDD}) suffix patterns.
     */
    public static String buildGa4EventNameDistinctSql(String qualifiedTableName, String tableName) {
        if (isGa4EventsShardTable(tableName)) {
            String wildcardRef = ga4EventsWildcardRef(qualifiedTableName);
            return "SELECT DISTINCT event_name FROM " + wildcardRef + " " +
                "WHERE " + GA4_EVENT_NAME_FILTER + " " +
                "AND (" +
                "  _TABLE_SUFFIX BETWEEN FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 2 DAY)) " +
                "    AND FORMAT_DATE('%Y%m%d', CURRENT_DATE()) " +
                "  OR _TABLE_SUFFIX BETWEEN CONCAT('intraday_', FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 2 DAY))) " +
                "    AND CONCAT('intraday_', FORMAT_DATE('%Y%m%d', CURRENT_DATE())) " +
                "  OR _TABLE_SUFFIX BETWEEN CONCAT('fresh_', FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 2 DAY))) " +
                "    AND CONCAT('fresh_', FORMAT_DATE('%Y%m%d', CURRENT_DATE())) " +
                ") ORDER BY event_name LIMIT 50";
        }
        return "SELECT DISTINCT event_name FROM " + qualifiedTableName +
            " WHERE " + GA4_EVENT_NAME_FILTER + " ORDER BY event_name LIMIT 50";
    }

    /**
     * Fallback: distinct event_name from the currently selected table only.
     */
    public static String buildGa4EventNameDistinctSqlSingleTable(String qualifiedTableName) {
        return "SELECT DISTINCT event_name FROM " + qualifiedTableName +
            " WHERE " + GA4_EVENT_NAME_FILTER + " ORDER BY event_name LIMIT 50";
    }

    /**
     * Drop invalid event_name tokens from a collected list (mutates and returns the same list).
     */
    public static List<String> filterValidGa4EventNames(List<String> eventNames) {
        if (eventNames == null || eventNames.isEmpty()) {
            return eventNames;
        }
        eventNames.removeIf(name -> !isValidGa4EventName(name));
        return eventNames;
    }

    /**
     * Get example value collection queries for custom-value columns in a predefined table.
     * These columns have user-defined values that vary per implementation,
     * so example values must be collected from actual data.
     *
     * @param service   service identifier
     * @param tableName table name
     * @param qualifiedTableName fully qualified BigQuery table name, already escaped
     *                         (e.g. {@code `project`.`dataset`.`events_20260101`})
     * @return map of column name -> SQL query to collect distinct values, or empty map if none
     */
    public static Map<String, String> getCustomValueQueries(String service, String tableName, String qualifiedTableName) {
        Map<String, String> queries = new LinkedHashMap<>();
        if (service == null || qualifiedTableName == null) return queries;

        String tableType = resolveTableType(service, tableName);
        if (tableType == null) return queries;

        switch (service + ":" + tableType) {
            case "GA4:events":
                // event_name: distinct event names from recent shards (wildcard covers
                // events_YYYYMMDD, events_intraday_YYYYMMDD, events_fresh_YYYYMMDD)
                queries.put("event_name", buildGa4EventNameDistinctSql(qualifiedTableName, tableName));

                // event_params.key: custom parameter keys
                queries.put("event_params.key",
                    "SELECT DISTINCT ep.key FROM " + qualifiedTableName + ", UNNEST(event_params) ep WHERE ep.key IS NOT NULL ORDER BY ep.key LIMIT 50");

                // event_params.value: a representative sample value per parameter key,
                // formatted as "key = value". This teaches the AI what each parameter
                // actually carries (e.g. "page_location = https://...", "value = 29.99",
                // "ga_session_id = 1712345678") so it can disambiguate the overloaded
                // value record across keys/events — the nested-structure counterpart of
                // the partition-aware per-event sampling used for flat event tables.
                queries.put("event_params.value",
                    "SELECT CONCAT(ep.key, ' = ', SUBSTR(COALESCE(" +
                    "ANY_VALUE(ep.value.string_value), " +
                    "CAST(ANY_VALUE(ep.value.int_value) AS STRING), " +
                    "CAST(ANY_VALUE(ep.value.double_value) AS STRING), " +
                    "CAST(ANY_VALUE(ep.value.float_value) AS STRING), '(empty)'), 1, 60)) " +
                    "FROM " + qualifiedTableName + ", UNNEST(event_params) ep " +
                    "WHERE ep.key IS NOT NULL GROUP BY ep.key ORDER BY ep.key LIMIT 50");

                // user_properties.key: custom user property keys
                queries.put("user_properties.key",
                    "SELECT DISTINCT up.key FROM " + qualifiedTableName + ", UNNEST(user_properties) up WHERE up.key IS NOT NULL ORDER BY up.key LIMIT 50");

                // user_properties.value: representative sample value per user property key (key = value)
                queries.put("user_properties.value",
                    "SELECT CONCAT(up.key, ' = ', SUBSTR(COALESCE(" +
                    "ANY_VALUE(up.value.string_value), " +
                    "CAST(ANY_VALUE(up.value.int_value) AS STRING), " +
                    "CAST(ANY_VALUE(up.value.double_value) AS STRING), " +
                    "CAST(ANY_VALUE(up.value.float_value) AS STRING), '(empty)'), 1, 60)) " +
                    "FROM " + qualifiedTableName + ", UNNEST(user_properties) up " +
                    "WHERE up.key IS NOT NULL GROUP BY up.key ORDER BY up.key LIMIT 50");

                // items.item_id: product IDs
                queries.put("items.item_id",
                    "SELECT DISTINCT it.item_id FROM " + qualifiedTableName + ", UNNEST(items) it WHERE it.item_id IS NOT NULL ORDER BY it.item_id LIMIT 30");

                // items.item_name: product names
                queries.put("items.item_name",
                    "SELECT DISTINCT it.item_name FROM " + qualifiedTableName + ", UNNEST(items) it WHERE it.item_name IS NOT NULL ORDER BY it.item_name LIMIT 30");

                // items.item_brand: brand names
                queries.put("items.item_brand",
                    "SELECT DISTINCT it.item_brand FROM " + qualifiedTableName + ", UNNEST(items) it WHERE it.item_brand IS NOT NULL ORDER BY it.item_brand LIMIT 30");

                // items.item_category: top-level categories
                queries.put("items.item_category",
                    "SELECT DISTINCT it.item_category FROM " + qualifiedTableName + ", UNNEST(items) it WHERE it.item_category IS NOT NULL ORDER BY it.item_category LIMIT 30");

                // items.item_params.key: custom item parameter keys
                queries.put("items.item_params.key",
                    "SELECT DISTINCT ip.key FROM " + qualifiedTableName + ", UNNEST(items) it, UNNEST(it.item_params) ip WHERE ip.key IS NOT NULL ORDER BY ip.key LIMIT 30");

                // items.item_params.value: representative sample value per item parameter key (key = value)
                queries.put("items.item_params.value",
                    "SELECT CONCAT(ip.key, ' = ', SUBSTR(COALESCE(" +
                    "ANY_VALUE(ip.value.string_value), " +
                    "CAST(ANY_VALUE(ip.value.int_value) AS STRING), " +
                    "CAST(ANY_VALUE(ip.value.double_value) AS STRING), " +
                    "CAST(ANY_VALUE(ip.value.float_value) AS STRING), '(empty)'), 1, 60)) " +
                    "FROM " + qualifiedTableName + ", UNNEST(items) it, UNNEST(it.item_params) ip " +
                    "WHERE ip.key IS NOT NULL GROUP BY ip.key ORDER BY ip.key LIMIT 30");

                // stream_id: data streams
                queries.put("stream_id",
                    "SELECT DISTINCT stream_id FROM " + qualifiedTableName + " WHERE stream_id IS NOT NULL LIMIT 10");

                break;

            default:
                break;
        }

        return queries;
    }

    /**
     * Convert a GA4 date-sharded table ref to a wildcard ref for cross-shard queries.
     * BigQuery requires wildcard tables as one quoted identifier, e.g.
     * {@code `project.dataset.events_*`} — not {@code `project`.`dataset`.events_*}
     * which is a syntax error ("Expected end of input but got '*'").
     */
    private static String ga4EventsWildcardRef(String qualifiedTableName) {
        if (qualifiedTableName == null) {
            return null;
        }
        String clean = qualifiedTableName.replace("`", "");
        String wildcardPath = clean.replaceAll("events(_intraday|_fresh)?_\\d{8}$", "events_*");
        return "`" + wildcardPath + "`";
    }

    private static boolean isGa4EventsShardTable(String tableName) {
        if (tableName == null) {
            return false;
        }
        return tableName.replace("`", "").matches("events(_intraday|_fresh)?_\\d{8}$");
    }

    /**
     * Resolve specific table type within a service.
     */
    private static String resolveTableType(String service, String tableName) {
        switch (service) {
            case "GA4":
                if (tableName.startsWith("events_intraday_") || tableName.startsWith("events_")) {
                    return "events";
                }
                if (tableName.startsWith("pseudonymous_users_")) {
                    return "pseudonymous_users";
                }
                if (tableName.startsWith("users_")) {
                    return "users";
                }
                return null;

            case "FIREBASE_CRASHLYTICS":
                return "crashlytics";

            case "FIREBASE_CLOUD_MESSAGING":
                return "messaging";

            case "FIREBASE_PERFORMANCE":
                return "performance";

            case "GOOGLE_SEARCH_CONSOLE":
                if (tableName.equals("searchdata_site_impression")) return "site_impression";
                if (tableName.equals("searchdata_url_impression")) return "url_impression";
                return null;

            case "CLOUD_BILLING":
                if (tableName.startsWith("gcp_billing_export_resource_v1_")) return "billing_detailed";
                if (tableName.startsWith("gcp_billing_export_v1_")) return "billing_standard";
                if (tableName.equals("cloud_pricing_export")) return "pricing";
                return null;

            case "CLOUD_LOGGING":
                return "log_entry";

            case "GOOGLE_WORKSPACE":
                if (tableName.startsWith("activity_")) return "activity";
                if (tableName.startsWith("usage_")) return "usage";
                return null;

            default:
                return null;
        }
    }

    // ========================================================================
    // GA4 (Google Analytics 4) Metadata
    // ========================================================================
    private static void registerGA4Metadata() {
        // events / events_intraday table
        METADATA_REGISTRY.put("GA4:events", new PredefinedTableMetadata(
            "Google Analytics 4 event data exported to BigQuery. Contains one row per event collected from websites and apps, " +
            "including page views, user interactions, e-commerce transactions, and custom events. " +
            "Date-sharded table (events_YYYYMMDD) with intraday variant (events_intraday_YYYYMMDD) for real-time data.",
            ga4EventsColumns()
        ));

        // pseudonymous_users table
        METADATA_REGISTRY.put("GA4:pseudonymous_users", new PredefinedTableMetadata(
            "Google Analytics 4 pseudonymous user data. Contains one row per pseudonymous user ID that had data changes on the given day. " +
            "Includes user properties, audience memberships, lifetime value metrics, and predictive scores. " +
            "Date-sharded table (pseudonymous_users_YYYYMMDD).",
            ga4PseudonymousUsersColumns()
        ));

        // users table
        METADATA_REGISTRY.put("GA4:users", new PredefinedTableMetadata(
            "Google Analytics 4 identified user data. Contains one row per user ID (set via setUserId API) that had data changes on the given day. " +
            "Schema is identical to pseudonymous_users but keyed by user_id instead of pseudo_user_id. " +
            "Date-sharded table (users_YYYYMMDD).",
            ga4UsersColumns()
        ));
    }

    private static List<PredefinedColumnMetadata> ga4EventsColumns() {
        List<PredefinedColumnMetadata> cols = new ArrayList<>();

        // Top-level fields
        cols.add(new PredefinedColumnMetadata("event_date", "Date when the event was logged in YYYYMMDD format, based on the property's timezone"));
        cols.add(new PredefinedColumnMetadata("event_timestamp", "Timestamp when the event was logged in microseconds (UTC)"));
        cols.add(new PredefinedColumnMetadata("event_previous_timestamp", "Timestamp when the event was previously logged in microseconds (UTC)"));
        cols.add(new PredefinedColumnMetadata("event_name", "Name of the event (e.g., page_view, purchase, session_start, first_visit, scroll)"));
        cols.add(new PredefinedColumnMetadata("event_value_in_usd", "Currency-converted value of the event in USD"));
        cols.add(new PredefinedColumnMetadata("event_bundle_sequence_id", "Sequential ID of the bundle in which these events were uploaded"));
        cols.add(new PredefinedColumnMetadata("event_server_timestamp_offset", "Timestamp offset between collection time and upload time in microseconds"));
        cols.add(new PredefinedColumnMetadata("batch_event_index", "Sequential number assigned to each event within a batch_page_id"));
        cols.add(new PredefinedColumnMetadata("batch_page_id", "Unique identifier for the group of events collected during a single page visit"));
        cols.add(new PredefinedColumnMetadata("batch_ordering_id", "Monotonically increasing ID for ordering batches of events"));
        cols.add(new PredefinedColumnMetadata("user_id", "User ID set via the setUserId API. Identifies a user across devices and sessions"));
        cols.add(new PredefinedColumnMetadata("user_pseudo_id", "Pseudonymous ID for the user (app instance ID or browser cookie-based identifier)"));
        cols.add(new PredefinedColumnMetadata("user_first_touch_timestamp", "Timestamp in microseconds (UTC) when the user first opened the app or visited the site"));
        cols.add(new PredefinedColumnMetadata("stream_id", "Numeric ID of the data stream from which the event originated"));
        cols.add(new PredefinedColumnMetadata("platform", "Platform on which the event originated: Web, IOS, or ANDROID"));
        cols.add(new PredefinedColumnMetadata("is_active_user", "Whether the user was active (TRUE) on the given calendar day"));

        // event_params (ARRAY<STRUCT>)
        cols.add(new PredefinedColumnMetadata("event_params", "Array of event parameters as key-value pairs. Common keys include: page_location, page_title, page_referrer, source, medium, campaign, session_id, ga_session_number, engagement_time_msec, entrances, percent_scrolled, link_url, search_term, transaction_id, value, currency"));
        cols.add(new PredefinedColumnMetadata("event_params.key", "Name of the event parameter"));
        cols.add(new PredefinedColumnMetadata("event_params.value", "Value record containing typed fields for the parameter value"));
        cols.add(new PredefinedColumnMetadata("event_params.value.string_value", "String value of the event parameter (if applicable)"));
        cols.add(new PredefinedColumnMetadata("event_params.value.int_value", "Integer value of the event parameter (if applicable)"));
        cols.add(new PredefinedColumnMetadata("event_params.value.double_value", "Double-precision floating-point value of the parameter (if applicable)"));
        cols.add(new PredefinedColumnMetadata("event_params.value.float_value", "Floating-point value of the parameter (if applicable)"));

        // user_properties (ARRAY<STRUCT>)
        cols.add(new PredefinedColumnMetadata("user_properties", "Array of user properties set via setUserProperty API, stored as key-value pairs with timestamps"));
        cols.add(new PredefinedColumnMetadata("user_properties.key", "Name of the user property"));
        cols.add(new PredefinedColumnMetadata("user_properties.value", "Value record for the user property"));
        cols.add(new PredefinedColumnMetadata("user_properties.value.string_value", "String value of the user property"));
        cols.add(new PredefinedColumnMetadata("user_properties.value.int_value", "Integer value of the user property"));
        cols.add(new PredefinedColumnMetadata("user_properties.value.double_value", "Double value of the user property"));
        cols.add(new PredefinedColumnMetadata("user_properties.value.float_value", "Float value of the user property"));
        cols.add(new PredefinedColumnMetadata("user_properties.value.set_timestamp_micros", "Timestamp in microseconds (UTC) when the user property was last set"));

        // user_ltv (STRUCT)
        cols.add(new PredefinedColumnMetadata("user_ltv", "Lifetime value information for the user. Not populated in events_intraday tables"));
        cols.add(new PredefinedColumnMetadata("user_ltv.revenue", "Lifetime value (total revenue) of the user"));
        cols.add(new PredefinedColumnMetadata("user_ltv.currency", "Currency code for the lifetime value revenue"));

        // device (STRUCT)
        cols.add(new PredefinedColumnMetadata("device", "Information about the device from which the event originated"));
        cols.add(new PredefinedColumnMetadata("device.category", "Device category: mobile, tablet, or desktop"));
        cols.add(new PredefinedColumnMetadata("device.mobile_brand_name", "Device brand name (e.g., Samsung, Apple, Huawei)"));
        cols.add(new PredefinedColumnMetadata("device.mobile_model_name", "Device model name (e.g., iPhone 14, SM-G998B)"));
        cols.add(new PredefinedColumnMetadata("device.mobile_marketing_name", "Device marketing name (e.g., Galaxy S21 Ultra)"));
        cols.add(new PredefinedColumnMetadata("device.mobile_os_hardware_model", "Device hardware model info reported by the operating system"));
        cols.add(new PredefinedColumnMetadata("device.operating_system", "Operating system of the device (e.g., Android, iOS, Windows, Macintosh)"));
        cols.add(new PredefinedColumnMetadata("device.operating_system_version", "OS version string"));
        cols.add(new PredefinedColumnMetadata("device.vendor_id", "IDFV (Identifier for Vendor), iOS only"));
        cols.add(new PredefinedColumnMetadata("device.advertising_id", "Advertising ID (IDFA/GAID). Not present if not collected"));
        cols.add(new PredefinedColumnMetadata("device.language", "OS language setting of the device"));
        cols.add(new PredefinedColumnMetadata("device.time_zone_offset_seconds", "Offset from GMT in seconds"));
        cols.add(new PredefinedColumnMetadata("device.is_limited_ad_tracking", "Whether the device has Limit Ad Tracking enabled (iOS) or opted out of Ads Personalization (Android)"));
        cols.add(new PredefinedColumnMetadata("device.browser", "Browser in which the user viewed content (legacy field, same as device.web_info.browser)"));
        cols.add(new PredefinedColumnMetadata("device.browser_version", "Version of the browser (legacy field, same as device.web_info.browser_version)"));
        cols.add(new PredefinedColumnMetadata("device.web_info", "Web-specific device information"));
        cols.add(new PredefinedColumnMetadata("device.web_info.browser", "Browser in which the user viewed content (e.g., Chrome, Safari, Firefox)"));
        cols.add(new PredefinedColumnMetadata("device.web_info.browser_version", "Version of the browser"));
        cols.add(new PredefinedColumnMetadata("device.web_info.hostname", "Hostname associated with the logged event"));

        // geo (STRUCT)
        cols.add(new PredefinedColumnMetadata("geo", "Geographic information derived from the user's IP address"));
        cols.add(new PredefinedColumnMetadata("geo.continent", "Continent from which events were reported (e.g., Americas, Europe, Asia)"));
        cols.add(new PredefinedColumnMetadata("geo.sub_continent", "Sub-continent from which events were reported (e.g., Northern America, Eastern Asia)"));
        cols.add(new PredefinedColumnMetadata("geo.country", "Country from which events were reported"));
        cols.add(new PredefinedColumnMetadata("geo.region", "Region (state or province) from which events were reported"));
        cols.add(new PredefinedColumnMetadata("geo.city", "City from which events were reported"));
        cols.add(new PredefinedColumnMetadata("geo.metro", "Designated Market Area (DMA) from which events were reported"));

        // app_info (STRUCT)
        cols.add(new PredefinedColumnMetadata("app_info", "Information about the mobile app (only for app streams)"));
        cols.add(new PredefinedColumnMetadata("app_info.id", "Package name (Android) or bundle ID (iOS) of the app"));
        cols.add(new PredefinedColumnMetadata("app_info.firebase_app_id", "Firebase App ID associated with the app"));
        cols.add(new PredefinedColumnMetadata("app_info.install_source", "Store that installed the app (e.g., Google Play Store, App Store)"));
        cols.add(new PredefinedColumnMetadata("app_info.version", "App version: versionName (Android) or short bundle version (iOS)"));
        cols.add(new PredefinedColumnMetadata("app_info.install_store", "The store from which the app was installed"));

        // traffic_source (STRUCT) - first-touch, user-level
        cols.add(new PredefinedColumnMetadata("traffic_source", "First-touch (user-level) traffic source attribution. Determined at first interaction and never updated"));
        cols.add(new PredefinedColumnMetadata("traffic_source.name", "Name of the marketing campaign that first acquired the user"));
        cols.add(new PredefinedColumnMetadata("traffic_source.medium", "Medium that first acquired the user (e.g., paid search, organic, email, (none))"));
        cols.add(new PredefinedColumnMetadata("traffic_source.source", "Source/network that first acquired the user (e.g., google, facebook, direct)"));

        // collected_traffic_source (STRUCT) - raw UTM values
        cols.add(new PredefinedColumnMetadata("collected_traffic_source", "Raw event-scoped traffic source data collected from UTM parameters and click IDs, without session scoping or attribution applied"));
        cols.add(new PredefinedColumnMetadata("collected_traffic_source.manual_campaign_id", "Campaign ID (utm_id) collected with the event"));
        cols.add(new PredefinedColumnMetadata("collected_traffic_source.manual_campaign_name", "Campaign name (utm_campaign) collected with the event"));
        cols.add(new PredefinedColumnMetadata("collected_traffic_source.manual_source", "Traffic source (utm_source) collected with the event"));
        cols.add(new PredefinedColumnMetadata("collected_traffic_source.manual_medium", "Traffic medium (utm_medium) collected with the event"));
        cols.add(new PredefinedColumnMetadata("collected_traffic_source.manual_term", "Keyword/term (utm_term) collected with the event"));
        cols.add(new PredefinedColumnMetadata("collected_traffic_source.manual_content", "Ad content (utm_content) collected with the event"));
        cols.add(new PredefinedColumnMetadata("collected_traffic_source.manual_source_platform", "Source platform (utm_source_platform) collected with the event"));
        cols.add(new PredefinedColumnMetadata("collected_traffic_source.manual_creative_format", "Creative format (utm_creative_format) collected with the event"));
        cols.add(new PredefinedColumnMetadata("collected_traffic_source.manual_marketing_tactic", "Marketing tactic (utm_marketing_tactic) collected with the event"));
        cols.add(new PredefinedColumnMetadata("collected_traffic_source.gclid", "Google Click Identifier (Google Ads click tracking)"));
        cols.add(new PredefinedColumnMetadata("collected_traffic_source.dclid", "DoubleClick click identifier (Google Marketing Platform)"));
        cols.add(new PredefinedColumnMetadata("collected_traffic_source.srsltid", "Google Merchant Center identifier"));

        // session_traffic_source_last_click (STRUCT) - session-level attribution
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click", "Session-scoped, last non-direct click attributed traffic source. Matches GA4 UI 'Session acquisition' report. Added July 2024"));

        // manual_campaign
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.manual_campaign", "Manual campaign attribution data for the session (UTM-based)"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.manual_campaign.campaign_id", "Campaign ID of the last clicked manual campaign in the session"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.manual_campaign.campaign_name", "Campaign name of the last clicked manual campaign"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.manual_campaign.source", "Traffic source of the last clicked manual campaign"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.manual_campaign.medium", "Traffic medium of the last clicked manual campaign"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.manual_campaign.term", "Keyword/search term of the last clicked manual campaign"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.manual_campaign.content", "Ad content of the last clicked manual campaign"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.manual_campaign.source_platform", "Source platform (e.g., Manual, SearchAds360)"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.manual_campaign.creative_format", "Creative format of the campaign ad"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.manual_campaign.marketing_tactic", "Marketing tactic of the campaign (e.g., prospecting, remarketing)"));

        // google_ads_campaign
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.google_ads_campaign", "Google Ads campaign attribution data for the session"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.google_ads_campaign.customer_id", "Google Ads customer/account ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.google_ads_campaign.account_name", "Google Ads account name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.google_ads_campaign.campaign_id", "Google Ads campaign ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.google_ads_campaign.campaign_name", "Google Ads campaign name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.google_ads_campaign.ad_group_id", "Google Ads ad group ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.google_ads_campaign.ad_group_name", "Google Ads ad group name"));

        // cross_channel_campaign
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cross_channel_campaign", "Cross-channel campaign attribution combining all integration sources into a unified view matching the GA4 UI"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cross_channel_campaign.campaign_id", "Cross-channel campaign ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cross_channel_campaign.campaign_name", "Cross-channel campaign name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cross_channel_campaign.source", "Cross-channel traffic source"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cross_channel_campaign.medium", "Cross-channel traffic medium"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cross_channel_campaign.source_platform", "Cross-channel source platform"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cross_channel_campaign.default_channel_group", "Default channel grouping for the session (e.g., Organic Search, Direct, Paid Search, Social)"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cross_channel_campaign.primary_channel_group", "Primary channel group classification for the session"));

        // sa360_campaign
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.sa360_campaign", "Search Ads 360 campaign attribution data"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.sa360_campaign.campaign_id", "SA360 campaign ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.sa360_campaign.campaign_name", "SA360 campaign name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.sa360_campaign.source", "SA360 traffic source"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.sa360_campaign.medium", "SA360 traffic medium"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.sa360_campaign.ad_group_id", "SA360 ad group ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.sa360_campaign.ad_group_name", "SA360 ad group name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.sa360_campaign.engine_account_id", "SA360 engine account ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.sa360_campaign.engine_account_name", "SA360 engine account name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.sa360_campaign.engine_account_type", "SA360 engine account type"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.sa360_campaign.creative_format", "SA360 creative format"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.sa360_campaign.manager_account_name", "SA360 manager account name"));

        // cm360_campaign
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign", "Campaign Manager 360 attribution data"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.campaign_id", "CM360 campaign ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.campaign_name", "CM360 campaign name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.source", "CM360 traffic source"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.medium", "CM360 traffic medium"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.account_id", "CM360 account ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.account_name", "CM360 account name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.advertiser_id", "CM360 advertiser ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.advertiser_name", "CM360 advertiser name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.creative_id", "CM360 creative ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.creative_format", "CM360 creative format"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.creative_name", "CM360 creative name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.creative_type", "CM360 creative type"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.creative_type_id", "CM360 creative type ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.creative_version", "CM360 creative version"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.placement_id", "CM360 placement ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.placement_name", "CM360 placement name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.placement_cost_structure", "CM360 placement cost structure"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.rendering_id", "CM360 rendering ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.site_id", "CM360 site ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.cm360_campaign.site_name", "CM360 site name"));

        // dv360_campaign
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign", "Display & Video 360 attribution data"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.campaign_id", "DV360 campaign ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.campaign_name", "DV360 campaign name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.source", "DV360 traffic source"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.medium", "DV360 traffic medium"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.advertiser_id", "DV360 advertiser ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.advertiser_name", "DV360 advertiser name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.creative_id", "DV360 creative ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.creative_format", "DV360 creative format"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.creative_name", "DV360 creative name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.exchange_id", "DV360 exchange ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.exchange_name", "DV360 exchange name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.insertion_order_id", "DV360 insertion order ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.insertion_order_name", "DV360 insertion order name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.line_item_id", "DV360 line item ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.line_item_name", "DV360 line item name"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.partner_id", "DV360 partner ID"));
        cols.add(new PredefinedColumnMetadata("session_traffic_source_last_click.dv360_campaign.partner_name", "DV360 partner name"));

        // privacy_info (STRUCT)
        cols.add(new PredefinedColumnMetadata("privacy_info", "Consent mode settings at the time of the event"));
        cols.add(new PredefinedColumnMetadata("privacy_info.ads_storage", "Whether ad storage consent is granted for the user: Yes, No, or Unset"));
        cols.add(new PredefinedColumnMetadata("privacy_info.analytics_storage", "Whether analytics storage consent is granted: Yes, No, or Unset"));
        cols.add(new PredefinedColumnMetadata("privacy_info.uses_transient_token", "Whether a web user denied analytics storage and measurement uses transient tokens"));

        // ecommerce (STRUCT)
        cols.add(new PredefinedColumnMetadata("ecommerce", "E-commerce transaction data associated with the event (purchase, refund events)"));
        cols.add(new PredefinedColumnMetadata("ecommerce.total_item_quantity", "Total number of items in the e-commerce event"));
        cols.add(new PredefinedColumnMetadata("ecommerce.purchase_revenue_in_usd", "Purchase revenue of the event in USD (after tax and shipping)"));
        cols.add(new PredefinedColumnMetadata("ecommerce.purchase_revenue", "Purchase revenue of the event in local currency"));
        cols.add(new PredefinedColumnMetadata("ecommerce.refund_value_in_usd", "Refund amount in USD"));
        cols.add(new PredefinedColumnMetadata("ecommerce.refund_value", "Refund amount in local currency"));
        cols.add(new PredefinedColumnMetadata("ecommerce.shipping_value_in_usd", "Shipping cost in USD"));
        cols.add(new PredefinedColumnMetadata("ecommerce.shipping_value", "Shipping cost in local currency"));
        cols.add(new PredefinedColumnMetadata("ecommerce.tax_value_in_usd", "Tax amount in USD"));
        cols.add(new PredefinedColumnMetadata("ecommerce.tax_value", "Tax amount in local currency"));
        cols.add(new PredefinedColumnMetadata("ecommerce.unique_items", "Number of unique items in the e-commerce event"));
        cols.add(new PredefinedColumnMetadata("ecommerce.transaction_id", "Transaction ID of the e-commerce transaction"));

        // items (ARRAY<STRUCT>)
        cols.add(new PredefinedColumnMetadata("items", "Array of item details for e-commerce events (purchase, add_to_cart, view_item, etc.)"));
        cols.add(new PredefinedColumnMetadata("items.item_id", "Item/product ID"));
        cols.add(new PredefinedColumnMetadata("items.item_name", "Item/product name"));
        cols.add(new PredefinedColumnMetadata("items.item_brand", "Item brand"));
        cols.add(new PredefinedColumnMetadata("items.item_variant", "Item variant (e.g., color, size)"));
        cols.add(new PredefinedColumnMetadata("items.item_category", "Primary item category"));
        cols.add(new PredefinedColumnMetadata("items.item_category2", "Item sub-category level 2"));
        cols.add(new PredefinedColumnMetadata("items.item_category3", "Item sub-category level 3"));
        cols.add(new PredefinedColumnMetadata("items.item_category4", "Item sub-category level 4"));
        cols.add(new PredefinedColumnMetadata("items.item_category5", "Item sub-category level 5"));
        cols.add(new PredefinedColumnMetadata("items.price_in_usd", "Item unit price in USD"));
        cols.add(new PredefinedColumnMetadata("items.price", "Item unit price in local currency"));
        cols.add(new PredefinedColumnMetadata("items.quantity", "Quantity of the item"));
        cols.add(new PredefinedColumnMetadata("items.item_revenue_in_usd", "Revenue from this item in USD (price * quantity)"));
        cols.add(new PredefinedColumnMetadata("items.item_revenue", "Revenue from this item in local currency"));
        cols.add(new PredefinedColumnMetadata("items.item_refund_in_usd", "Refund value for this item in USD"));
        cols.add(new PredefinedColumnMetadata("items.item_refund", "Refund value for this item in local currency"));
        cols.add(new PredefinedColumnMetadata("items.coupon", "Coupon code applied to this item"));
        cols.add(new PredefinedColumnMetadata("items.affiliation", "Product affiliation to designate a supplying company or store"));
        cols.add(new PredefinedColumnMetadata("items.location_id", "Location associated with the item (e.g., physical store location)"));
        cols.add(new PredefinedColumnMetadata("items.item_list_id", "ID of the list in which the item was presented to the user"));
        cols.add(new PredefinedColumnMetadata("items.item_list_name", "Name of the list in which the item was presented"));
        cols.add(new PredefinedColumnMetadata("items.item_list_index", "Position/index of the item in the list"));
        cols.add(new PredefinedColumnMetadata("items.promotion_id", "ID of the promotion associated with the item"));
        cols.add(new PredefinedColumnMetadata("items.promotion_name", "Name of the promotion associated with the item"));
        cols.add(new PredefinedColumnMetadata("items.creative_name", "Name of the creative used for the promotion"));
        cols.add(new PredefinedColumnMetadata("items.creative_slot", "Name/position of the creative slot"));
        cols.add(new PredefinedColumnMetadata("items.item_params", "Array of custom item-scoped parameters as key-value pairs"));
        cols.add(new PredefinedColumnMetadata("items.item_params.key", "Name of the custom item parameter"));
        cols.add(new PredefinedColumnMetadata("items.item_params.value", "Value record for the custom item parameter"));
        cols.add(new PredefinedColumnMetadata("items.item_params.value.string_value", "String value of the item parameter"));
        cols.add(new PredefinedColumnMetadata("items.item_params.value.int_value", "Integer value of the item parameter"));
        cols.add(new PredefinedColumnMetadata("items.item_params.value.float_value", "Float value of the item parameter"));
        cols.add(new PredefinedColumnMetadata("items.item_params.value.double_value", "Double value of the item parameter"));

        // event_dimensions (STRUCT)
        cols.add(new PredefinedColumnMetadata("event_dimensions", "Additional event dimensions"));
        cols.add(new PredefinedColumnMetadata("event_dimensions.hostname", "Hostname where the event was logged"));

        // publisher (STRUCT)
        cols.add(new PredefinedColumnMetadata("publisher", "Publisher/ad monetization data (for apps using AdMob or similar ad networks)"));
        cols.add(new PredefinedColumnMetadata("publisher.ad_revenue_in_usd", "Ad revenue earned from this event in USD"));
        cols.add(new PredefinedColumnMetadata("publisher.ad_format", "Ad format (e.g., banner, interstitial, rewarded)"));
        cols.add(new PredefinedColumnMetadata("publisher.ad_source_name", "Source network name that served the ad"));
        cols.add(new PredefinedColumnMetadata("publisher.ad_unit_id", "Ad unit identifier"));

        return cols;
    }

    private static List<PredefinedColumnMetadata> ga4PseudonymousUsersColumns() {
        List<PredefinedColumnMetadata> cols = new ArrayList<>();

        // Top-level
        cols.add(new PredefinedColumnMetadata("pseudo_user_id", "Pseudonymous identifier for the user (app instance ID or cookie-based)"));
        cols.add(new PredefinedColumnMetadata("stream_id", "Numeric ID of the data stream"));
        cols.add(new PredefinedColumnMetadata("occurrence_date", "Date when a record change was triggered (YYYYMMDD format)"));
        cols.add(new PredefinedColumnMetadata("last_updated_date", "Date when the record was last updated (YYYYMMDD format)"));

        // user_info (STRUCT)
        cols.add(new PredefinedColumnMetadata("user_info", "Basic user information and activity timestamps"));
        cols.add(new PredefinedColumnMetadata("user_info.last_active_timestamp_micros", "Timestamp in microseconds (UTC) of the user's last activity"));
        cols.add(new PredefinedColumnMetadata("user_info.user_first_touch_timestamp_micros", "Timestamp in microseconds (UTC) of the user's first app open or site visit"));
        cols.add(new PredefinedColumnMetadata("user_info.first_purchase_date", "Date of the user's first purchase (YYYYMMDD format)"));

        // privacy_info (STRUCT)
        cols.add(new PredefinedColumnMetadata("privacy_info", "Privacy and consent settings for the user"));
        cols.add(new PredefinedColumnMetadata("privacy_info.is_ads_personalization_allowed", "Whether ads personalization is allowed for the user: true, false, or (not set)"));
        cols.add(new PredefinedColumnMetadata("privacy_info.is_limited_ad_tracking", "Whether the device has Limit Ad Tracking enabled: true, false, or (not set)"));

        // user_properties (ARRAY<STRUCT>)
        cols.add(new PredefinedColumnMetadata("user_properties", "Array of user properties set via setUserProperty API"));
        cols.add(new PredefinedColumnMetadata("user_properties.key", "Name of the user property"));
        cols.add(new PredefinedColumnMetadata("user_properties.value", "Value record for the user property"));
        cols.add(new PredefinedColumnMetadata("user_properties.value.string_value", "String value of the user property"));
        cols.add(new PredefinedColumnMetadata("user_properties.value.int_value", "Integer value of the user property"));
        cols.add(new PredefinedColumnMetadata("user_properties.value.float_value", "Float value of the user property"));
        cols.add(new PredefinedColumnMetadata("user_properties.value.double_value", "Double value of the user property"));
        cols.add(new PredefinedColumnMetadata("user_properties.value.set_timestamp_micros", "Timestamp in microseconds (UTC) when the property was last set"));

        // audiences (ARRAY<STRUCT>)
        cols.add(new PredefinedColumnMetadata("audiences", "Array of GA4 audiences the user belongs to"));
        cols.add(new PredefinedColumnMetadata("audiences.id", "Internal audience identifier"));
        cols.add(new PredefinedColumnMetadata("audiences.name", "User-assigned audience name in GA4"));
        cols.add(new PredefinedColumnMetadata("audiences.membership_start_timestamp_micros", "Timestamp in microseconds when the user first joined the audience"));
        cols.add(new PredefinedColumnMetadata("audiences.membership_expiry_timestamp_micros", "Timestamp in microseconds when the user's audience membership expires"));
        cols.add(new PredefinedColumnMetadata("audiences.npa", "Whether the audience is marked as non-personalized ads (NPA)"));

        // device (STRUCT)
        cols.add(new PredefinedColumnMetadata("device", "Information about the user's device"));
        cols.add(new PredefinedColumnMetadata("device.operating_system", "Operating system of the device"));
        cols.add(new PredefinedColumnMetadata("device.category", "Device category: mobile, tablet, or desktop"));
        cols.add(new PredefinedColumnMetadata("device.mobile_brand_name", "Device brand name"));
        cols.add(new PredefinedColumnMetadata("device.mobile_model_name", "Device model name"));
        cols.add(new PredefinedColumnMetadata("device.unified_screen_name", "Screen name last seen by the user"));

        // geo (STRUCT)
        cols.add(new PredefinedColumnMetadata("geo", "Geographic information based on IP address"));
        cols.add(new PredefinedColumnMetadata("geo.city", "City of the user"));
        cols.add(new PredefinedColumnMetadata("geo.country", "Country of the user"));
        cols.add(new PredefinedColumnMetadata("geo.continent", "Continent of the user"));
        cols.add(new PredefinedColumnMetadata("geo.region", "Region (state/province) of the user"));

        // user_ltv (STRUCT)
        cols.add(new PredefinedColumnMetadata("user_ltv", "Lifetime value metrics for the user"));
        cols.add(new PredefinedColumnMetadata("user_ltv.revenue_in_usd", "Total lifetime revenue in USD"));
        cols.add(new PredefinedColumnMetadata("user_ltv.sessions", "Lifetime total session count"));
        cols.add(new PredefinedColumnMetadata("user_ltv.engagement_time_millis", "Lifetime total engagement time in milliseconds"));
        cols.add(new PredefinedColumnMetadata("user_ltv.purchases", "Lifetime total number of purchases"));
        cols.add(new PredefinedColumnMetadata("user_ltv.engaged_sessions", "Lifetime total number of engaged sessions"));
        cols.add(new PredefinedColumnMetadata("user_ltv.session_duration_micros", "Lifetime total session duration in microseconds"));

        // predictions (STRUCT)
        cols.add(new PredefinedColumnMetadata("predictions", "Machine learning-based predictive metrics. Requires sufficient data volume to be populated"));
        cols.add(new PredefinedColumnMetadata("predictions.in_app_purchase_score_7d", "Probability (0-1) that the user will make an in-app purchase within 7 days"));
        cols.add(new PredefinedColumnMetadata("predictions.purchase_score_7d", "Probability (0-1) that the user will make any purchase within 7 days"));
        cols.add(new PredefinedColumnMetadata("predictions.churn_score_7d", "Probability (0-1) that the user will not be active within the next 7 days"));
        cols.add(new PredefinedColumnMetadata("predictions.revenue_28d_in_usd", "Predicted revenue from the user over the next 28 days in USD"));

        return cols;
    }

    private static List<PredefinedColumnMetadata> ga4UsersColumns() {
        // Same structure as pseudonymous_users but with user_id instead of pseudo_user_id
        List<PredefinedColumnMetadata> cols = ga4PseudonymousUsersColumns();

        // Replace pseudo_user_id with user_id
        cols.set(0, new PredefinedColumnMetadata("user_id", "User ID set via the setUserId API. Identifies a user across devices and sessions"));

        return cols;
    }

    /**
     * Helper class for table name pattern entries.
     */
    private static class TablePatternEntry {
        final Pattern pattern;
        final String service;

        TablePatternEntry(Pattern pattern, String service) {
            this.pattern = pattern;
            this.service = service;
        }
    }

    // ========================================================================
    // Firebase Crashlytics Metadata
    // ========================================================================
    private static void registerFirebaseCrashlyticsMetadata() {
        METADATA_REGISTRY.put("FIREBASE_CRASHLYTICS:crashlytics", new PredefinedTableMetadata(
            "Firebase Crashlytics crash and error data exported to BigQuery. Contains one row per crash, non-fatal error, or ANR event " +
            "collected from mobile apps. Includes stack traces, device info, custom keys, and logs. " +
            "Batch table per app/platform with optional realtime variant (*_REALTIME).",
            crashlyticsColumns()
        ));
    }

    private static List<PredefinedColumnMetadata> crashlyticsColumns() {
        List<PredefinedColumnMetadata> cols = new ArrayList<>();

        // Top-level fields
        cols.add(new PredefinedColumnMetadata("event_id", "Unique identifier for the crash/error event"));
        cols.add(new PredefinedColumnMetadata("event_timestamp", "Timestamp when the crash/error event occurred"));
        cols.add(new PredefinedColumnMetadata("issue_id", "Crashlytics issue ID that groups related crashes"));
        cols.add(new PredefinedColumnMetadata("variant_id", "Issue variant identifier for distinguishing sub-groups of the same issue"));
        cols.add(new PredefinedColumnMetadata("error_type", "Type of error: FATAL (crash), NON_FATAL (handled error), or ANR (Application Not Responding)"));
        cols.add(new PredefinedColumnMetadata("is_fatal", "Whether the event was a fatal crash (deprecated - use error_type instead)"));
        cols.add(new PredefinedColumnMetadata("platform", "Platform: IOS or ANDROID"));
        cols.add(new PredefinedColumnMetadata("bundle_identifier", "App package name (Android) or bundle ID (iOS)"));
        cols.add(new PredefinedColumnMetadata("crashlytics_sdk_versions", "Version of the Crashlytics SDK used by the app"));
        cols.add(new PredefinedColumnMetadata("installation_uuid", "Unique identifier for the app installation on a device"));
        cols.add(new PredefinedColumnMetadata("firebase_session_id", "Firebase session ID associated with the event"));
        cols.add(new PredefinedColumnMetadata("app_orientation", "App UI orientation at the time of crash (PORTRAIT, LANDSCAPE, etc.)"));
        cols.add(new PredefinedColumnMetadata("device_orientation", "Physical device orientation at the time of crash"));
        cols.add(new PredefinedColumnMetadata("process_state", "App process state: BACKGROUND or FOREGROUND"));

        // application (STRUCT)
        cols.add(new PredefinedColumnMetadata("application", "Information about the app that crashed"));
        cols.add(new PredefinedColumnMetadata("application.build_version", "App build version (versionCode on Android, CFBundleVersion on iOS)"));
        cols.add(new PredefinedColumnMetadata("application.display_version", "App display version (versionName on Android, CFBundleShortVersionString on iOS)"));

        // device (STRUCT)
        cols.add(new PredefinedColumnMetadata("device", "Information about the device where the crash occurred"));
        cols.add(new PredefinedColumnMetadata("device.manufacturer", "Device manufacturer (e.g., Samsung, Apple, Google)"));
        cols.add(new PredefinedColumnMetadata("device.model", "Device model identifier (e.g., iPhone12,1, SM-G998B)"));
        cols.add(new PredefinedColumnMetadata("device.architecture", "CPU architecture (e.g., arm64, x86_64)"));

        // memory (STRUCT)
        cols.add(new PredefinedColumnMetadata("memory", "Device memory usage at the time of crash"));
        cols.add(new PredefinedColumnMetadata("memory.used", "Used memory in bytes at the time of crash"));
        cols.add(new PredefinedColumnMetadata("memory.free", "Free memory in bytes at the time of crash"));

        // storage (STRUCT)
        cols.add(new PredefinedColumnMetadata("storage", "Device storage usage at the time of crash"));
        cols.add(new PredefinedColumnMetadata("storage.used", "Used storage in bytes at the time of crash"));
        cols.add(new PredefinedColumnMetadata("storage.free", "Free storage in bytes at the time of crash"));

        // operating_system (STRUCT)
        cols.add(new PredefinedColumnMetadata("operating_system", "Operating system information"));
        cols.add(new PredefinedColumnMetadata("operating_system.name", "OS name (e.g., Android, iOS)"));
        cols.add(new PredefinedColumnMetadata("operating_system.display_version", "OS version string displayed to user (e.g., 14.0, 13)"));
        cols.add(new PredefinedColumnMetadata("operating_system.device_type", "Device type classification"));
        cols.add(new PredefinedColumnMetadata("operating_system.modification_state", "OS modification state: MODIFIED (jailbroken/rooted) or UNMODIFIED"));
        cols.add(new PredefinedColumnMetadata("operating_system.type", "OS type (Apple apps only)"));

        // user (STRUCT)
        cols.add(new PredefinedColumnMetadata("user", "User information set via Crashlytics SDK"));
        cols.add(new PredefinedColumnMetadata("user.id", "User identifier set via Crashlytics.setUserId()"));
        cols.add(new PredefinedColumnMetadata("user.name", "User name (deprecated)"));
        cols.add(new PredefinedColumnMetadata("user.email", "User email (deprecated)"));

        // blame_frame (STRUCT)
        cols.add(new PredefinedColumnMetadata("blame_frame", "The single stack frame identified as the root cause of the crash"));
        cols.add(new PredefinedColumnMetadata("blame_frame.line", "Line number in source code"));
        cols.add(new PredefinedColumnMetadata("blame_frame.file", "Source file name"));
        cols.add(new PredefinedColumnMetadata("blame_frame.symbol", "Symbol/method name"));
        cols.add(new PredefinedColumnMetadata("blame_frame.offset", "Byte offset within the binary image (native crashes)"));
        cols.add(new PredefinedColumnMetadata("blame_frame.address", "Memory address of the instruction (native crashes)"));
        cols.add(new PredefinedColumnMetadata("blame_frame.library", "Library/binary image name containing this frame"));
        cols.add(new PredefinedColumnMetadata("blame_frame.owner", "Frame ownership: DEVELOPER, VENDOR, RUNTIME, PLATFORM, or SYSTEM"));
        cols.add(new PredefinedColumnMetadata("blame_frame.blamed", "Whether this frame was identified as the cause of the crash"));

        // custom_keys (ARRAY<STRUCT>)
        cols.add(new PredefinedColumnMetadata("custom_keys", "Array of custom key-value pairs set via Crashlytics.setCustomKey()"));
        cols.add(new PredefinedColumnMetadata("custom_keys.key", "Custom key name"));
        cols.add(new PredefinedColumnMetadata("custom_keys.value", "Custom key value"));

        // logs (ARRAY<STRUCT>)
        cols.add(new PredefinedColumnMetadata("logs", "Array of log messages written via Crashlytics.log() before the crash"));
        cols.add(new PredefinedColumnMetadata("logs.timestamp", "Timestamp when the log message was written"));
        cols.add(new PredefinedColumnMetadata("logs.message", "Log message content"));

        // breadcrumbs (ARRAY<STRUCT>)
        cols.add(new PredefinedColumnMetadata("breadcrumbs", "Array of Google Analytics breadcrumb events logged before the crash"));
        cols.add(new PredefinedColumnMetadata("breadcrumbs.timestamp", "Timestamp of the breadcrumb event"));
        cols.add(new PredefinedColumnMetadata("breadcrumbs.name", "Name of the breadcrumb event"));
        cols.add(new PredefinedColumnMetadata("breadcrumbs.params", "Array of key-value parameters for the breadcrumb event"));
        cols.add(new PredefinedColumnMetadata("breadcrumbs.params.key", "Breadcrumb parameter key"));
        cols.add(new PredefinedColumnMetadata("breadcrumbs.params.value", "Breadcrumb parameter value"));

        // threads (ARRAY<STRUCT>)
        cols.add(new PredefinedColumnMetadata("threads", "Array of all threads and their stack traces at the time of crash"));
        cols.add(new PredefinedColumnMetadata("threads.crashed", "Whether this thread was the one that crashed"));
        cols.add(new PredefinedColumnMetadata("threads.thread_name", "Name of the thread"));
        cols.add(new PredefinedColumnMetadata("threads.title", "Thread title (crash type/exception info)"));
        cols.add(new PredefinedColumnMetadata("threads.subtitle", "Thread subtitle (additional crash context)"));
        cols.add(new PredefinedColumnMetadata("threads.blamed", "Whether this thread was blamed for the crash"));
        cols.add(new PredefinedColumnMetadata("threads.queue_name", "Apple dispatch queue name (iOS only)"));
        cols.add(new PredefinedColumnMetadata("threads.signal_name", "Signal name for native crashes (e.g., SIGSEGV, SIGABRT)"));
        cols.add(new PredefinedColumnMetadata("threads.signal_code", "Signal code for native crashes"));
        cols.add(new PredefinedColumnMetadata("threads.crash_address", "Memory address where native crash occurred"));
        cols.add(new PredefinedColumnMetadata("threads.code", "Error code associated with the thread"));
        cols.add(new PredefinedColumnMetadata("threads.frames", "Array of stack frames for this thread"));
        cols.add(new PredefinedColumnMetadata("threads.frames.line", "Line number in source code"));
        cols.add(new PredefinedColumnMetadata("threads.frames.file", "Source file name"));
        cols.add(new PredefinedColumnMetadata("threads.frames.symbol", "Symbol/method name"));
        cols.add(new PredefinedColumnMetadata("threads.frames.offset", "Byte offset within the binary image (native frames)"));
        cols.add(new PredefinedColumnMetadata("threads.frames.address", "Memory address of the instruction (native frames)"));
        cols.add(new PredefinedColumnMetadata("threads.frames.library", "Library/binary image name containing this frame"));
        cols.add(new PredefinedColumnMetadata("threads.frames.owner", "Frame ownership: DEVELOPER, VENDOR, RUNTIME, PLATFORM, or SYSTEM"));
        cols.add(new PredefinedColumnMetadata("threads.frames.blamed", "Whether this frame was identified as the cause"));

        // exceptions (ARRAY<STRUCT>) - Android only
        cols.add(new PredefinedColumnMetadata("exceptions", "Array of Java/Kotlin exceptions (Android only). Contains exception chain for the crash"));
        cols.add(new PredefinedColumnMetadata("exceptions.type", "Exception class name (e.g., NullPointerException, IllegalStateException)"));
        cols.add(new PredefinedColumnMetadata("exceptions.exception_message", "Exception message string"));
        cols.add(new PredefinedColumnMetadata("exceptions.nested", "Whether this is a nested/chained exception (cause)"));
        cols.add(new PredefinedColumnMetadata("exceptions.title", "Exception title"));
        cols.add(new PredefinedColumnMetadata("exceptions.subtitle", "Exception subtitle with additional context"));
        cols.add(new PredefinedColumnMetadata("exceptions.blamed", "Whether this exception was identified as the root cause"));
        cols.add(new PredefinedColumnMetadata("exceptions.frames", "Array of stack frames for this exception"));
        cols.add(new PredefinedColumnMetadata("exceptions.frames.line", "Line number in source code"));
        cols.add(new PredefinedColumnMetadata("exceptions.frames.file", "Source file name"));
        cols.add(new PredefinedColumnMetadata("exceptions.frames.symbol", "Symbol/method name"));
        cols.add(new PredefinedColumnMetadata("exceptions.frames.offset", "Byte offset within the binary image"));
        cols.add(new PredefinedColumnMetadata("exceptions.frames.address", "Memory address of the instruction"));
        cols.add(new PredefinedColumnMetadata("exceptions.frames.library", "Library name containing this frame"));
        cols.add(new PredefinedColumnMetadata("exceptions.frames.owner", "Frame ownership: DEVELOPER, VENDOR, RUNTIME, PLATFORM, or SYSTEM"));
        cols.add(new PredefinedColumnMetadata("exceptions.frames.blamed", "Whether this frame was identified as the cause"));

        // error (ARRAY<STRUCT>) - Apple only
        cols.add(new PredefinedColumnMetadata("error", "Array of error information (Apple/iOS only)"));
        cols.add(new PredefinedColumnMetadata("error.queue_name", "Apple dispatch queue name"));
        cols.add(new PredefinedColumnMetadata("error.code", "Error code"));
        cols.add(new PredefinedColumnMetadata("error.title", "Error title"));
        cols.add(new PredefinedColumnMetadata("error.subtitle", "Error subtitle"));
        cols.add(new PredefinedColumnMetadata("error.blamed", "Whether this error was identified as the root cause"));
        cols.add(new PredefinedColumnMetadata("error.frames", "Array of stack frames for this error"));
        cols.add(new PredefinedColumnMetadata("error.frames.line", "Line number in source code"));
        cols.add(new PredefinedColumnMetadata("error.frames.file", "Source file name"));
        cols.add(new PredefinedColumnMetadata("error.frames.symbol", "Symbol/method name"));
        cols.add(new PredefinedColumnMetadata("error.frames.offset", "Byte offset within the binary image"));
        cols.add(new PredefinedColumnMetadata("error.frames.address", "Memory address of the instruction"));
        cols.add(new PredefinedColumnMetadata("error.frames.library", "Library name containing this frame"));
        cols.add(new PredefinedColumnMetadata("error.frames.owner", "Frame ownership: DEVELOPER, VENDOR, RUNTIME, PLATFORM, or SYSTEM"));
        cols.add(new PredefinedColumnMetadata("error.frames.blamed", "Whether this frame was identified as the cause"));

        // unity_metadata (STRUCT) - Unity apps only
        cols.add(new PredefinedColumnMetadata("unity_metadata", "Unity engine metadata (Unity apps only)"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.unity_version", "Unity engine version"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.debug_build", "Whether the build is a debug build"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.processor_type", "CPU processor type"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.processor_count", "Number of CPU cores"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.processor_frequency_mhz", "CPU frequency in MHz"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.system_memory_size_mb", "Total system memory in MB"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.graphics_memory_size_mb", "GPU memory size in MB"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.graphics_device_name", "GPU device name"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.graphics_device_vendor", "GPU device vendor"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.graphics_device_id", "GPU device ID"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.graphics_device_vendor_id", "GPU vendor ID"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.graphics_device_type", "GPU device type (e.g., Metal, Vulkan, OpenGL)"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.graphics_device_version", "GPU device/driver version"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.graphics_shader_level", "Supported shader model level"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.graphics_render_target_count", "Number of supported render targets"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.graphics_copy_texture_support", "Copy texture support capabilities"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.graphics_max_texture_size", "Maximum supported texture size"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.screen_size_px", "Screen resolution in pixels (e.g., 1080x1920)"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.screen_resolution_dpi", "Screen DPI"));
        cols.add(new PredefinedColumnMetadata("unity_metadata.screen_refresh_rate_hz", "Screen refresh rate in Hz"));

        return cols;
    }

    // ========================================================================
    // Firebase Cloud Messaging (FCM) Metadata
    // ========================================================================
    private static void registerFirebaseCloudMessagingMetadata() {
        METADATA_REGISTRY.put("FIREBASE_CLOUD_MESSAGING:messaging", new PredefinedTableMetadata(
            "Firebase Cloud Messaging delivery data exported to BigQuery. Contains one row per message lifecycle event, " +
            "tracking message acceptance, delivery, and errors. Flat schema with no nested fields. " +
            "Batch table per app/platform with optional realtime variant (*_REALTIME).",
            fcmColumns()
        ));
    }

    private static List<PredefinedColumnMetadata> fcmColumns() {
        List<PredefinedColumnMetadata> cols = new ArrayList<>();

        cols.add(new PredefinedColumnMetadata("event_timestamp", "Server-recorded timestamp of the message lifecycle event"));
        cols.add(new PredefinedColumnMetadata("project_number", "Firebase project number that sent the message"));
        cols.add(new PredefinedColumnMetadata("message_id", "Message identifier (generated from App ID + timestamp)"));
        cols.add(new PredefinedColumnMetadata("instance_id", "Unique ID of the recipient app installation (Instance ID or Firebase Installation ID)"));
        cols.add(new PredefinedColumnMetadata("message_type", "Message type: NOTIFICATION, DATA, or TOPIC"));
        cols.add(new PredefinedColumnMetadata("sdk_platform", "Platform of the recipient app (e.g., ANDROID, IOS)"));
        cols.add(new PredefinedColumnMetadata("app_name", "Android package name or iOS bundle identifier"));
        cols.add(new PredefinedColumnMetadata("collapse_key", "Collapse key for collapsible messages; only the last message in the group is delivered when device comes online"));
        cols.add(new PredefinedColumnMetadata("priority", "Message priority: 5 = normal, 10 = high"));
        cols.add(new PredefinedColumnMetadata("ttl", "Time-to-live in seconds (how long FCM retains the message when device is offline)"));
        cols.add(new PredefinedColumnMetadata("topic", "Topic name (populated only for topic-targeted messages)"));
        cols.add(new PredefinedColumnMetadata("bulk_id", "Bulk identifier grouping related messages (e.g., a single topic send fans out to many recipients)"));
        cols.add(new PredefinedColumnMetadata("event", "Message lifecycle event: MESSAGE_ACCEPTED, MESSAGE_DELIVERED, MISSING_REGISTRATIONS, UNAUTHORIZED, QUOTA_EXCEEDED, INVALID_APNS_CREDENTIAL, THIRD_PARTY_AUTH_ERROR, etc."));
        cols.add(new PredefinedColumnMetadata("analytics_label", "Custom analytics label for filtering and reporting in the FCM console"));

        return cols;
    }

    // ========================================================================
    // Firebase Performance Monitoring Metadata
    // ========================================================================
    private static void registerFirebasePerformanceMetadata() {
        METADATA_REGISTRY.put("FIREBASE_PERFORMANCE:performance", new PredefinedTableMetadata(
            "Firebase Performance Monitoring data exported to BigQuery. Contains one row per performance event, " +
            "including app start traces, screen rendering traces, custom traces, and network requests. " +
            "Batch table per app/platform with optional realtime variant (*_REALTIME).",
            performanceColumns()
        ));
    }

    private static List<PredefinedColumnMetadata> performanceColumns() {
        List<PredefinedColumnMetadata> cols = new ArrayList<>();

        // Top-level fields
        cols.add(new PredefinedColumnMetadata("event_timestamp", "Timestamp when the performance event started on the client device"));
        cols.add(new PredefinedColumnMetadata("app_display_version", "App display version (Android: versionName, iOS: CFBundleShortVersionString)"));
        cols.add(new PredefinedColumnMetadata("app_build_version", "App build version (Android: versionCode, iOS: CFBundleVersion)"));
        cols.add(new PredefinedColumnMetadata("os_version", "Operating system version of the client device"));
        cols.add(new PredefinedColumnMetadata("device_name", "Client device model name"));
        cols.add(new PredefinedColumnMetadata("country", "Two-letter ISO country code (ZZ for unknown)"));
        cols.add(new PredefinedColumnMetadata("carrier", "Cellular carrier of the device"));
        cols.add(new PredefinedColumnMetadata("radio_type", "Active radio/connection type at event time (e.g., WIFI, LTE, 4G)"));
        cols.add(new PredefinedColumnMetadata("event_type", "Performance event type: DURATION_TRACE, SCREEN_TRACE, TRACE_METRIC, or NETWORK_REQUEST"));
        cols.add(new PredefinedColumnMetadata("event_name", "Event name: trace name for traces, URL pattern for network requests, screen class for screen traces"));
        cols.add(new PredefinedColumnMetadata("parent_trace_name", "Parent trace name (populated only for TRACE_METRIC events)"));

        // custom_attributes (ARRAY<STRUCT>)
        cols.add(new PredefinedColumnMetadata("custom_attributes", "Array of custom attributes attached to the performance event"));
        cols.add(new PredefinedColumnMetadata("custom_attributes.key", "Custom attribute key"));
        cols.add(new PredefinedColumnMetadata("custom_attributes.value", "Custom attribute value"));

        // trace_info (STRUCT)
        cols.add(new PredefinedColumnMetadata("trace_info", "Trace-specific data (for DURATION_TRACE, SCREEN_TRACE, TRACE_METRIC events)"));
        cols.add(new PredefinedColumnMetadata("trace_info.duration_us", "Trace duration in microseconds"));
        cols.add(new PredefinedColumnMetadata("trace_info.screen_info", "Screen rendering metrics (SCREEN_TRACE events only)"));
        cols.add(new PredefinedColumnMetadata("trace_info.screen_info.slow_frame_ratio", "Ratio of slow frames (>16ms render time), range 0.0-1.0"));
        cols.add(new PredefinedColumnMetadata("trace_info.screen_info.frozen_frame_ratio", "Ratio of frozen frames (>700ms render time), range 0.0-1.0"));
        cols.add(new PredefinedColumnMetadata("trace_info.metric_info", "Custom metric data (TRACE_METRIC events only)"));
        cols.add(new PredefinedColumnMetadata("trace_info.metric_info.metric_value", "Numeric value of the custom metric"));

        // network_info (STRUCT)
        cols.add(new PredefinedColumnMetadata("network_info", "HTTP request data (NETWORK_REQUEST events only)"));
        cols.add(new PredefinedColumnMetadata("network_info.response_code", "HTTP response status code (e.g., 200, 404, 500)"));
        cols.add(new PredefinedColumnMetadata("network_info.response_mime_type", "MIME type of the response (e.g., application/json)"));
        cols.add(new PredefinedColumnMetadata("network_info.request_http_method", "HTTP method (e.g., GET, POST, PUT)"));
        cols.add(new PredefinedColumnMetadata("network_info.request_payload_bytes", "Size of request payload in bytes"));
        cols.add(new PredefinedColumnMetadata("network_info.response_payload_bytes", "Size of response payload in bytes"));
        cols.add(new PredefinedColumnMetadata("network_info.request_completed_time_us", "Microseconds after event_timestamp when request sending completed"));
        cols.add(new PredefinedColumnMetadata("network_info.response_initiated_time_us", "Microseconds after event_timestamp when first response byte received"));
        cols.add(new PredefinedColumnMetadata("network_info.response_completed_time_us", "Microseconds after event_timestamp when full response received"));

        return cols;
    }

    // ========================================================================
    // Google Search Console Metadata
    // ========================================================================
    private static void registerGoogleSearchConsoleMetadata() {
        METADATA_REGISTRY.put("GOOGLE_SEARCH_CONSOLE:site_impression", new PredefinedTableMetadata(
            "Google Search Console site-level impression data. Contains aggregated search performance metrics at the property level, " +
            "including queries, clicks, impressions, and position data across search types and devices.",
            searchConsoleSiteColumns()
        ));

        METADATA_REGISTRY.put("GOOGLE_SEARCH_CONSOLE:url_impression", new PredefinedTableMetadata(
            "Google Search Console URL-level impression data. Contains search performance metrics per individual URL, " +
            "with rich result appearance flags and search type breakdowns.",
            searchConsoleUrlColumns()
        ));
    }

    private static List<PredefinedColumnMetadata> searchConsoleSiteColumns() {
        List<PredefinedColumnMetadata> cols = new ArrayList<>();

        cols.add(new PredefinedColumnMetadata("data_date", "Date of the search impression/click (Pacific time)"));
        cols.add(new PredefinedColumnMetadata("site_url", "Search Console property URL (domain or URL-prefix)"));
        cols.add(new PredefinedColumnMetadata("query", "User's search query string"));
        cols.add(new PredefinedColumnMetadata("is_anonymized_query", "Whether the query was anonymized to protect user privacy"));
        cols.add(new PredefinedColumnMetadata("country", "Three-letter country code (ISO 3166-1 alpha-3) of the searcher"));
        cols.add(new PredefinedColumnMetadata("search_type", "Search type: WEB, IMAGE, VIDEO, NEWS, or DISCOVER"));
        cols.add(new PredefinedColumnMetadata("device", "Device category: DESKTOP, MOBILE, or TABLET"));
        cols.add(new PredefinedColumnMetadata("impressions", "Number of times the property appeared in search results"));
        cols.add(new PredefinedColumnMetadata("clicks", "Number of user clicks from search results to the property"));
        cols.add(new PredefinedColumnMetadata("sum_top_position", "Sum of topmost position (0-based) across impressions. Average position = sum_top_position / impressions + 1"));

        return cols;
    }

    private static List<PredefinedColumnMetadata> searchConsoleUrlColumns() {
        List<PredefinedColumnMetadata> cols = new ArrayList<>();

        cols.add(new PredefinedColumnMetadata("data_date", "Date of the search impression/click (Pacific time)"));
        cols.add(new PredefinedColumnMetadata("site_url", "Search Console property URL"));
        cols.add(new PredefinedColumnMetadata("url", "Fully-qualified URL the user lands on when clicking the result"));
        cols.add(new PredefinedColumnMetadata("query", "User's search query string"));
        cols.add(new PredefinedColumnMetadata("is_anonymized_query", "Whether the query was anonymized for privacy"));
        cols.add(new PredefinedColumnMetadata("is_anonymized_discover", "Whether the URL is anonymized for Discover to protect user privacy"));
        cols.add(new PredefinedColumnMetadata("country", "Three-letter country code (ISO 3166-1 alpha-3)"));
        cols.add(new PredefinedColumnMetadata("search_type", "Search type: WEB, IMAGE, VIDEO, NEWS, or DISCOVER"));
        cols.add(new PredefinedColumnMetadata("device", "Device category: DESKTOP, MOBILE, or TABLET"));
        cols.add(new PredefinedColumnMetadata("impressions", "Number of impressions for this URL"));
        cols.add(new PredefinedColumnMetadata("clicks", "Number of clicks for this URL"));
        cols.add(new PredefinedColumnMetadata("sum_position", "Sum of position (0-based) across impressions"));
        cols.add(new PredefinedColumnMetadata("is_amp_top_stories", "Appeared as AMP in Top Stories carousel"));
        cols.add(new PredefinedColumnMetadata("is_amp_blue_link", "Appeared as AMP blue link in results"));
        cols.add(new PredefinedColumnMetadata("is_amp_story", "Appeared as a Web Story (AMP Story)"));
        cols.add(new PredefinedColumnMetadata("is_amp_image_result", "Appeared as AMP image result"));
        cols.add(new PredefinedColumnMetadata("is_job_listing", "Appeared as a job listing rich result"));
        cols.add(new PredefinedColumnMetadata("is_job_details", "Appeared as job details rich result"));
        cols.add(new PredefinedColumnMetadata("is_tpf_qa", "Appeared as Q&A rich result"));
        cols.add(new PredefinedColumnMetadata("is_tpf_faq", "Appeared as FAQ rich result"));
        cols.add(new PredefinedColumnMetadata("is_tpf_howto", "Appeared as HowTo rich result"));
        cols.add(new PredefinedColumnMetadata("is_action", "Appeared with an Action markup result"));
        cols.add(new PredefinedColumnMetadata("is_events_listing", "Appeared as events listing rich result"));
        cols.add(new PredefinedColumnMetadata("is_events_details", "Appeared as events details rich result"));
        cols.add(new PredefinedColumnMetadata("is_search_appearance_android_app", "Appeared as an Android app result"));
        cols.add(new PredefinedColumnMetadata("is_video", "Appeared as a video result"));
        cols.add(new PredefinedColumnMetadata("is_weblite", "Appeared as a Web Light result"));
        cols.add(new PredefinedColumnMetadata("is_organic_shopping", "Appeared as organic shopping result"));
        cols.add(new PredefinedColumnMetadata("is_review_snippet", "Appeared with review snippet markup"));
        cols.add(new PredefinedColumnMetadata("is_special_announcement", "Appeared as special announcement"));
        cols.add(new PredefinedColumnMetadata("is_recipe_feature", "Appeared in recipe feature/carousel"));
        cols.add(new PredefinedColumnMetadata("is_recipe_rich_snippet", "Appeared with recipe rich snippet"));
        cols.add(new PredefinedColumnMetadata("is_subscribed_content", "Appeared as subscribed/paywalled content"));
        cols.add(new PredefinedColumnMetadata("is_page_experience", "Page experience signal indicator"));
        cols.add(new PredefinedColumnMetadata("is_practice_problems", "Appeared as practice problems (education)"));
        cols.add(new PredefinedColumnMetadata("is_math_solvers", "Appeared as math solver result"));
        cols.add(new PredefinedColumnMetadata("is_translated_result", "Appeared as a translated result"));
        cols.add(new PredefinedColumnMetadata("is_edu_q_and_a", "Appeared as education Q&A result"));
        cols.add(new PredefinedColumnMetadata("is_richresult", "Appeared with any generic rich result"));

        return cols;
    }

    // ========================================================================
    // Google Cloud Billing Metadata
    // ========================================================================
    private static void registerCloudBillingMetadata() {
        METADATA_REGISTRY.put("CLOUD_BILLING:billing_standard", new PredefinedTableMetadata(
            "Google Cloud Billing standard usage cost data. Contains one row per usage line item with service, SKU, project, " +
            "cost, credits, and labels. Partitioned by usage_start_time.",
            billingStandardColumns()
        ));

        // Detailed billing has the same columns plus resource info
        List<PredefinedColumnMetadata> detailedCols = new ArrayList<>(billingStandardColumns());
        detailedCols.add(new PredefinedColumnMetadata("resource", "Resource-level details (detailed export only)"));
        detailedCols.add(new PredefinedColumnMetadata("resource.global_name", "Globally unique service identifier for the resource"));
        detailedCols.add(new PredefinedColumnMetadata("resource.name", "Service-specific identifier for the resource"));
        detailedCols.add(new PredefinedColumnMetadata("subscription", "Subscription details linked to a commitment"));
        detailedCols.add(new PredefinedColumnMetadata("subscription.instance_id", "Subscription ID linked to a commitment"));
        METADATA_REGISTRY.put("CLOUD_BILLING:billing_detailed", new PredefinedTableMetadata(
            "Google Cloud Billing detailed usage cost data. Same as standard export plus resource-level identifiers. " +
            "Partitioned by usage_start_time.",
            detailedCols
        ));

        METADATA_REGISTRY.put("CLOUD_BILLING:pricing", new PredefinedTableMetadata(
            "Google Cloud pricing data export. Contains list prices and account-specific contract prices for all SKUs, " +
            "with tiered rate information.",
            pricingColumns()
        ));
    }

    private static List<PredefinedColumnMetadata> billingStandardColumns() {
        List<PredefinedColumnMetadata> cols = new ArrayList<>();

        cols.add(new PredefinedColumnMetadata("billing_account_id", "Cloud Billing account ID"));
        cols.add(new PredefinedColumnMetadata("invoice", "Invoice information"));
        cols.add(new PredefinedColumnMetadata("invoice.month", "Invoice month in YYYYMM format"));
        cols.add(new PredefinedColumnMetadata("invoice.publisher_type", "Publisher type: GOOGLE or PARTNER"));
        cols.add(new PredefinedColumnMetadata("cost_type", "Line item category: regular, tax, adjustment, or rounding_error"));
        cols.add(new PredefinedColumnMetadata("service", "Google Cloud service information"));
        cols.add(new PredefinedColumnMetadata("service.id", "Service identifier code"));
        cols.add(new PredefinedColumnMetadata("service.description", "Google Cloud service name (e.g., Compute Engine, Cloud Storage)"));
        cols.add(new PredefinedColumnMetadata("sku", "Resource/SKU information"));
        cols.add(new PredefinedColumnMetadata("sku.id", "Resource/SKU identifier"));
        cols.add(new PredefinedColumnMetadata("sku.description", "Description of the resource type used"));
        cols.add(new PredefinedColumnMetadata("usage_start_time", "Start of the hourly usage window"));
        cols.add(new PredefinedColumnMetadata("usage_end_time", "End of the hourly usage window"));
        cols.add(new PredefinedColumnMetadata("project", "Google Cloud project information"));
        cols.add(new PredefinedColumnMetadata("project.id", "Google Cloud project ID"));
        cols.add(new PredefinedColumnMetadata("project.number", "Internally-generated unique project number"));
        cols.add(new PredefinedColumnMetadata("project.name", "User-assigned project display name"));
        cols.add(new PredefinedColumnMetadata("project.ancestry_numbers", "Resource hierarchy ancestor IDs"));
        cols.add(new PredefinedColumnMetadata("project.ancestors", "Array of ancestor resources in the hierarchy"));
        cols.add(new PredefinedColumnMetadata("project.ancestors.resource_name", "Fully qualified ancestor resource name"));
        cols.add(new PredefinedColumnMetadata("project.ancestors.display_name", "Ancestor display name"));
        cols.add(new PredefinedColumnMetadata("project.labels", "Project-level labels"));
        cols.add(new PredefinedColumnMetadata("project.labels.key", "Project label key"));
        cols.add(new PredefinedColumnMetadata("project.labels.value", "Project label value"));
        cols.add(new PredefinedColumnMetadata("labels", "Resource-level labels"));
        cols.add(new PredefinedColumnMetadata("labels.key", "Resource label key"));
        cols.add(new PredefinedColumnMetadata("labels.value", "Resource label value"));
        cols.add(new PredefinedColumnMetadata("system_labels", "System-generated labels"));
        cols.add(new PredefinedColumnMetadata("system_labels.key", "System label key"));
        cols.add(new PredefinedColumnMetadata("system_labels.value", "System label value"));
        cols.add(new PredefinedColumnMetadata("location", "Usage location information"));
        cols.add(new PredefinedColumnMetadata("location.location", "Usage location (region, zone, or global)"));
        cols.add(new PredefinedColumnMetadata("location.country", "Country code where usage occurred"));
        cols.add(new PredefinedColumnMetadata("location.region", "Region identifier (e.g., us-central1)"));
        cols.add(new PredefinedColumnMetadata("location.zone", "Zone identifier (e.g., us-central1-a)"));
        cols.add(new PredefinedColumnMetadata("cost", "Amount charged based on consumption model"));
        cols.add(new PredefinedColumnMetadata("currency", "Billing currency code (ISO 4217)"));
        cols.add(new PredefinedColumnMetadata("currency_conversion_rate", "Exchange rate from USD to local currency"));
        cols.add(new PredefinedColumnMetadata("usage", "Resource usage quantity information"));
        cols.add(new PredefinedColumnMetadata("usage.amount", "Quantity consumed in base units"));
        cols.add(new PredefinedColumnMetadata("usage.unit", "Base measurement unit"));
        cols.add(new PredefinedColumnMetadata("usage.amount_in_pricing_units", "Quantity in pricing unit measurement"));
        cols.add(new PredefinedColumnMetadata("usage.pricing_unit", "Pricing measurement unit"));
        cols.add(new PredefinedColumnMetadata("credits", "Array of credits applied to this line item"));
        cols.add(new PredefinedColumnMetadata("credits.id", "Credit identifier"));
        cols.add(new PredefinedColumnMetadata("credits.full_name", "Human-readable credit name"));
        cols.add(new PredefinedColumnMetadata("credits.type", "Credit type (e.g., SUSTAINED_USAGE_DISCOUNT, COMMITTED_USAGE_DISCOUNT)"));
        cols.add(new PredefinedColumnMetadata("credits.name", "Credit description"));
        cols.add(new PredefinedColumnMetadata("credits.amount", "Credit amount (negative value reduces cost)"));
        cols.add(new PredefinedColumnMetadata("adjustment_info", "Cost adjustment information"));
        cols.add(new PredefinedColumnMetadata("adjustment_info.id", "Adjustment group identifier"));
        cols.add(new PredefinedColumnMetadata("adjustment_info.description", "Adjustment explanation"));
        cols.add(new PredefinedColumnMetadata("adjustment_info.type", "Adjustment category: CORRECTION or MODIFICATION"));
        cols.add(new PredefinedColumnMetadata("adjustment_info.mode", "How adjustment was issued"));
        cols.add(new PredefinedColumnMetadata("export_time", "Data export processing timestamp"));
        cols.add(new PredefinedColumnMetadata("tags", "Resource tags"));
        cols.add(new PredefinedColumnMetadata("tags.key", "Tag key"));
        cols.add(new PredefinedColumnMetadata("tags.value", "Tag value"));
        cols.add(new PredefinedColumnMetadata("tags.inherited", "Whether tag is inherited from parent"));
        cols.add(new PredefinedColumnMetadata("tags.namespace", "Tag resource hierarchy identifier"));
        cols.add(new PredefinedColumnMetadata("cost_at_list", "Cost calculated at default list price"));
        cols.add(new PredefinedColumnMetadata("transaction_type", "Seller transaction type: GOOGLE, THIRD_PARTY_RESELLER, or THIRD_PARTY_AGENCY"));
        cols.add(new PredefinedColumnMetadata("seller_name", "Legal seller entity name"));
        cols.add(new PredefinedColumnMetadata("price", "Price information"));
        cols.add(new PredefinedColumnMetadata("price.effective_price", "Applicable model price with discounts"));
        cols.add(new PredefinedColumnMetadata("price.tier_start_amount", "Pricing tier lower bound"));
        cols.add(new PredefinedColumnMetadata("price.unit", "Price measurement unit"));
        cols.add(new PredefinedColumnMetadata("price.pricing_unit_quantity", "Tier unit quantity"));

        return cols;
    }

    private static List<PredefinedColumnMetadata> pricingColumns() {
        List<PredefinedColumnMetadata> cols = new ArrayList<>();

        cols.add(new PredefinedColumnMetadata("export_time", "Processing time for the data append"));
        cols.add(new PredefinedColumnMetadata("pricing_as_of_time", "Timestamp when pricing data was generated"));
        cols.add(new PredefinedColumnMetadata("billing_account_id", "Cloud Billing account ID"));
        cols.add(new PredefinedColumnMetadata("business_entity_name", "Google service family (GCP or Maps)"));
        cols.add(new PredefinedColumnMetadata("service", "Service information"));
        cols.add(new PredefinedColumnMetadata("service.id", "Service identifier"));
        cols.add(new PredefinedColumnMetadata("service.description", "Human-readable service name"));
        cols.add(new PredefinedColumnMetadata("sku", "SKU information"));
        cols.add(new PredefinedColumnMetadata("sku.id", "Unique resource SKU identifier"));
        cols.add(new PredefinedColumnMetadata("sku.description", "SKU description"));
        cols.add(new PredefinedColumnMetadata("product_taxonomy", "List of product categories for the SKU"));
        cols.add(new PredefinedColumnMetadata("geo_taxonomy", "Geographic metadata"));
        cols.add(new PredefinedColumnMetadata("geo_taxonomy.type", "Geographic type: GLOBAL, REGIONAL, or MULTI_REGION"));
        cols.add(new PredefinedColumnMetadata("geo_taxonomy.regions", "Google Cloud regions associated with the SKU"));
        cols.add(new PredefinedColumnMetadata("pricing_unit", "Unit of usage in pricing"));
        cols.add(new PredefinedColumnMetadata("pricing_unit_description", "Human-readable pricing unit description"));
        cols.add(new PredefinedColumnMetadata("account_currency_code", "Three-letter ISO 4217 currency code"));
        cols.add(new PredefinedColumnMetadata("currency_conversion_rate", "Exchange rate from USD to local currency"));
        cols.add(new PredefinedColumnMetadata("list_price", "Default list price information"));
        cols.add(new PredefinedColumnMetadata("list_price.aggregation_info", "Aggregation level and interval"));
        cols.add(new PredefinedColumnMetadata("list_price.aggregation_info.aggregation_level", "Level: ACCOUNT, PROJECT, or UNKNOWN"));
        cols.add(new PredefinedColumnMetadata("list_price.aggregation_info.aggregation_interval", "Interval: ONE_DAY, ONE_MONTH, or UNKNOWN"));
        cols.add(new PredefinedColumnMetadata("list_price.tiered_rates", "Array of tiered pricing rates"));
        cols.add(new PredefinedColumnMetadata("list_price.tiered_rates.pricing_unit_quantity", "Unit quantity for the tier"));
        cols.add(new PredefinedColumnMetadata("list_price.tiered_rates.start_usage_amount", "Lower bound usage amount for the tier"));
        cols.add(new PredefinedColumnMetadata("list_price.tiered_rates.usd_amount", "Price in USD"));
        cols.add(new PredefinedColumnMetadata("list_price.tiered_rates.account_currency_amount", "Price in account currency"));
        cols.add(new PredefinedColumnMetadata("billing_account_price", "Account-specific contract price information"));
        cols.add(new PredefinedColumnMetadata("billing_account_price.price_info", "Price origin and discount details"));
        cols.add(new PredefinedColumnMetadata("billing_account_price.price_info.price_reason", "Price origin: DEFAULT_PRICE, FIXED_PRICE, FIXED_DISCOUNT, etc."));
        cols.add(new PredefinedColumnMetadata("billing_account_price.price_info.discount_percent", "Percentage discount applied"));
        cols.add(new PredefinedColumnMetadata("billing_account_price.aggregation_info", "Aggregation level and interval"));
        cols.add(new PredefinedColumnMetadata("billing_account_price.aggregation_info.aggregation_level", "Level: ACCOUNT, PROJECT, or UNKNOWN"));
        cols.add(new PredefinedColumnMetadata("billing_account_price.aggregation_info.aggregation_interval", "Interval: ONE_DAY, ONE_MONTH, or UNKNOWN"));
        cols.add(new PredefinedColumnMetadata("billing_account_price.tiered_rates", "Array of contract tiered pricing rates"));
        cols.add(new PredefinedColumnMetadata("billing_account_price.tiered_rates.pricing_unit_quantity", "Unit quantity for the tier"));
        cols.add(new PredefinedColumnMetadata("billing_account_price.tiered_rates.start_usage_amount", "Lower bound usage amount"));
        cols.add(new PredefinedColumnMetadata("billing_account_price.tiered_rates.usd_amount", "Contract price in USD"));
        cols.add(new PredefinedColumnMetadata("billing_account_price.tiered_rates.account_currency_amount", "Contract price in account currency"));

        return cols;
    }

    // ========================================================================
    // Google Cloud Logging Metadata
    // ========================================================================
    private static void registerCloudLoggingMetadata() {
        METADATA_REGISTRY.put("CLOUD_LOGGING:log_entry", new PredefinedTableMetadata(
            "Google Cloud Logging log entries exported to BigQuery via log sink. Contains structured log data including " +
            "HTTP requests, audit trail, resource metadata, and payloads. Date-sharded tables.",
            cloudLoggingColumns()
        ));
    }

    private static List<PredefinedColumnMetadata> cloudLoggingColumns() {
        List<PredefinedColumnMetadata> cols = new ArrayList<>();

        cols.add(new PredefinedColumnMetadata("logName", "Resource name of the log (e.g., projects/[PROJECT_ID]/logs/[LOG_ID])"));
        cols.add(new PredefinedColumnMetadata("resource", "Monitored resource that produced the log entry"));
        cols.add(new PredefinedColumnMetadata("resource.type", "Monitored resource type (e.g., gce_instance, gcs_bucket, bigquery_resource)"));
        cols.add(new PredefinedColumnMetadata("resource.labels", "Key-value labels for the monitored resource"));
        cols.add(new PredefinedColumnMetadata("timestamp", "Time the event described by the log entry occurred"));
        cols.add(new PredefinedColumnMetadata("receiveTimestamp", "Time the log entry was received by Cloud Logging"));
        cols.add(new PredefinedColumnMetadata("severity", "Log severity: DEFAULT, DEBUG, INFO, NOTICE, WARNING, ERROR, CRITICAL, ALERT, or EMERGENCY"));
        cols.add(new PredefinedColumnMetadata("insertId", "Unique identifier for the log entry (used for deduplication)"));
        cols.add(new PredefinedColumnMetadata("trace", "Resource name of the trace associated with the log entry"));
        cols.add(new PredefinedColumnMetadata("spanId", "Cloud Trace span ID associated with the current operation"));
        cols.add(new PredefinedColumnMetadata("traceSampled", "Sampling decision of the trace span"));
        cols.add(new PredefinedColumnMetadata("labels", "Key-value pairs providing additional metadata about the log entry"));
        cols.add(new PredefinedColumnMetadata("httpRequest", "HTTP request information associated with the log entry"));
        cols.add(new PredefinedColumnMetadata("httpRequest.requestMethod", "HTTP method (e.g., GET, POST, PUT, DELETE)"));
        cols.add(new PredefinedColumnMetadata("httpRequest.requestUrl", "Scheme, host, path, and query portion of the URL"));
        cols.add(new PredefinedColumnMetadata("httpRequest.requestSize", "HTTP request message size in bytes"));
        cols.add(new PredefinedColumnMetadata("httpRequest.status", "HTTP response status code (e.g., 200, 404)"));
        cols.add(new PredefinedColumnMetadata("httpRequest.responseSize", "HTTP response message size in bytes"));
        cols.add(new PredefinedColumnMetadata("httpRequest.userAgent", "User agent string sent by the client"));
        cols.add(new PredefinedColumnMetadata("httpRequest.remoteIp", "IP address of the client"));
        cols.add(new PredefinedColumnMetadata("httpRequest.serverIp", "IP address of the origin server"));
        cols.add(new PredefinedColumnMetadata("httpRequest.referer", "Referer URL of the request"));
        cols.add(new PredefinedColumnMetadata("httpRequest.latency", "Request processing latency on the server"));
        cols.add(new PredefinedColumnMetadata("httpRequest.cacheLookup", "Whether a cache lookup was attempted"));
        cols.add(new PredefinedColumnMetadata("httpRequest.cacheHit", "Whether an entity was served from cache"));
        cols.add(new PredefinedColumnMetadata("httpRequest.cacheValidatedWithOriginServer", "Whether the response was validated with origin server"));
        cols.add(new PredefinedColumnMetadata("httpRequest.cacheFillBytes", "Number of HTTP response bytes inserted into cache"));
        cols.add(new PredefinedColumnMetadata("httpRequest.protocol", "Protocol used (e.g., HTTP/1.1, HTTP/2, websocket)"));
        cols.add(new PredefinedColumnMetadata("operation", "Operation information for correlating related log entries"));
        cols.add(new PredefinedColumnMetadata("operation.id", "Arbitrary operation identifier"));
        cols.add(new PredefinedColumnMetadata("operation.producer", "Arbitrary producer identifier"));
        cols.add(new PredefinedColumnMetadata("operation.first", "Whether this is the first log entry in the operation"));
        cols.add(new PredefinedColumnMetadata("operation.last", "Whether this is the last log entry in the operation"));
        cols.add(new PredefinedColumnMetadata("sourceLocation", "Source code location of the log statement"));
        cols.add(new PredefinedColumnMetadata("sourceLocation.file", "Source file name"));
        cols.add(new PredefinedColumnMetadata("sourceLocation.line", "Line number within the source file"));
        cols.add(new PredefinedColumnMetadata("sourceLocation.function", "Human-readable name of the function/method"));
        cols.add(new PredefinedColumnMetadata("split", "Information about split log entries"));
        cols.add(new PredefinedColumnMetadata("split.uid", "Globally unique ID for all entries in the split sequence"));
        cols.add(new PredefinedColumnMetadata("split.index", "Index of this entry in the split sequence"));
        cols.add(new PredefinedColumnMetadata("split.totalSplits", "Total number of entries the original was split into"));
        cols.add(new PredefinedColumnMetadata("textPayload", "Log entry payload as plain text"));
        cols.add(new PredefinedColumnMetadata("jsonPayload", "Log entry payload as JSON structure"));
        // Audit log specific
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog", "Audit log payload (for audit log tables)"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.serviceName", "API service performing the operation (e.g., bigquery.googleapis.com)"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.methodName", "Service method/operation name"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.resourceName", "Resource that is the target of the operation"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.numResponseItems", "Number of items returned from a List/Query API method"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.status", "Operation status"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.status.code", "Status code (0 = OK, non-zero = error)"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.status.message", "Developer-facing error message"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.authenticationInfo", "Authentication information"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.authenticationInfo.principalEmail", "Email of the authenticated user/service account"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.authorizationInfo", "Authorization details for the operation"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.authorizationInfo.resource", "Resource being accessed"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.authorizationInfo.permission", "Required IAM permission"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.authorizationInfo.granted", "Whether permission was granted"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.requestMetadata", "Request metadata"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.requestMetadata.callerIp", "IP address of the caller"));
        cols.add(new PredefinedColumnMetadata("protopayload_auditlog.requestMetadata.callerSuppliedUserAgent", "User agent of the caller"));

        return cols;
    }

    // ========================================================================
    // Google Workspace Metadata
    // ========================================================================
    private static void registerGoogleWorkspaceMetadata() {
        METADATA_REGISTRY.put("GOOGLE_WORKSPACE:activity", new PredefinedTableMetadata(
            "Google Workspace audit activity logs exported to BigQuery. Contains all audit events from Admin SDK Reports API " +
            "including login, admin, Drive, Calendar, Gmail, and other Workspace services. Date-sharded tables.",
            workspaceActivityColumns()
        ));

        METADATA_REGISTRY.put("GOOGLE_WORKSPACE:usage", new PredefinedTableMetadata(
            "Google Workspace usage statistics exported to BigQuery. Contains aggregated usage metrics across " +
            "Workspace services including accounts, Calendar, Meet, Drive, and Gmail. Date-sharded tables.",
            workspaceUsageColumns()
        ));
    }

    private static List<PredefinedColumnMetadata> workspaceActivityColumns() {
        List<PredefinedColumnMetadata> cols = new ArrayList<>();

        cols.add(new PredefinedColumnMetadata("time_usec", "Timestamp of the activity in Unix microseconds. Use TIMESTAMP_MICROS() to convert"));
        cols.add(new PredefinedColumnMetadata("email", "Email address of the user who performed the action"));
        cols.add(new PredefinedColumnMetadata("ip_address", "IP address of the user performing the action"));
        cols.add(new PredefinedColumnMetadata("record_type", "Application/service name (e.g., login, admin, drive, calendar, token, groups, mobile, meet, chat)"));
        cols.add(new PredefinedColumnMetadata("event_type", "Type of event within the application"));
        cols.add(new PredefinedColumnMetadata("event_name", "Specific action name within the event type"));
        cols.add(new PredefinedColumnMetadata("admin", "Admin action details"));
        cols.add(new PredefinedColumnMetadata("admin.group_email", "Group email affected by the admin action"));
        cols.add(new PredefinedColumnMetadata("admin.user_email", "User email affected by the admin action"));
        cols.add(new PredefinedColumnMetadata("admin.new_value", "New value after the admin change"));
        cols.add(new PredefinedColumnMetadata("admin.old_value", "Previous value before the admin change"));
        cols.add(new PredefinedColumnMetadata("admin.setting_name", "Name of the admin setting changed"));
        cols.add(new PredefinedColumnMetadata("drive", "Google Drive event details"));
        cols.add(new PredefinedColumnMetadata("drive.doc_id", "Drive document ID"));
        cols.add(new PredefinedColumnMetadata("drive.doc_title", "Document title"));
        cols.add(new PredefinedColumnMetadata("drive.doc_type", "Document type (document, spreadsheet, presentation, folder)"));
        cols.add(new PredefinedColumnMetadata("drive.owner", "Email of the document owner"));
        cols.add(new PredefinedColumnMetadata("drive.visibility", "Document visibility (private, people_with_link, public_on_the_web, shared_internally, shared_externally)"));
        cols.add(new PredefinedColumnMetadata("drive.target_user", "Target user of the sharing action"));
        cols.add(new PredefinedColumnMetadata("login", "Login event details"));
        cols.add(new PredefinedColumnMetadata("login.login_type", "Type of login (google_password, saml, exchange)"));
        cols.add(new PredefinedColumnMetadata("login.login_failure_type", "Reason for login failure if applicable"));
        cols.add(new PredefinedColumnMetadata("calendar", "Calendar event details"));
        cols.add(new PredefinedColumnMetadata("calendar.calendar_id", "Calendar ID affected"));
        cols.add(new PredefinedColumnMetadata("token", "OAuth token event details"));
        cols.add(new PredefinedColumnMetadata("token.client_id", "OAuth client ID"));
        cols.add(new PredefinedColumnMetadata("token.scope", "OAuth scope granted"));
        cols.add(new PredefinedColumnMetadata("token.app_name", "Application name for the OAuth token"));
        cols.add(new PredefinedColumnMetadata("rules", "Rule trigger details"));
        cols.add(new PredefinedColumnMetadata("rules.rule_name", "Name of the triggered rule"));
        cols.add(new PredefinedColumnMetadata("rules.application", "Application the rule applies to"));
        cols.add(new PredefinedColumnMetadata("rules.resource_title", "Title of the resource the rule was triggered on"));
        cols.add(new PredefinedColumnMetadata("rules.actions", "Actions taken by the rule"));

        return cols;
    }

    private static List<PredefinedColumnMetadata> workspaceUsageColumns() {
        List<PredefinedColumnMetadata> cols = new ArrayList<>();

        cols.add(new PredefinedColumnMetadata("date", "Date of the usage record"));
        cols.add(new PredefinedColumnMetadata("user_email", "User email (for per-user usage records)"));
        cols.add(new PredefinedColumnMetadata("accounts", "Account-level usage metrics"));
        cols.add(new PredefinedColumnMetadata("accounts.num_users", "Total number of users"));
        cols.add(new PredefinedColumnMetadata("accounts.num_locked_users", "Number of locked user accounts"));
        cols.add(new PredefinedColumnMetadata("accounts.num_disabled_accounts", "Number of disabled accounts"));
        cols.add(new PredefinedColumnMetadata("accounts.num_suspended_users", "Number of suspended user accounts"));
        cols.add(new PredefinedColumnMetadata("accounts.num_super_admins", "Number of super admin accounts"));
        cols.add(new PredefinedColumnMetadata("accounts.num_delegated_admins", "Number of delegated admin accounts"));
        cols.add(new PredefinedColumnMetadata("accounts.is_super_admin", "Whether the user is a super admin (per-user records)"));
        cols.add(new PredefinedColumnMetadata("accounts.drive_used_quota_in_mb", "Drive storage used in MB"));
        cols.add(new PredefinedColumnMetadata("accounts.gmail_used_quota_in_mb", "Gmail storage used in MB"));
        cols.add(new PredefinedColumnMetadata("calendar", "Calendar usage metrics"));
        cols.add(new PredefinedColumnMetadata("calendar.num_1day_active_users", "Users active on Calendar in the last day"));
        cols.add(new PredefinedColumnMetadata("calendar.num_30day_active_users", "Users active on Calendar in the last 30 days"));
        cols.add(new PredefinedColumnMetadata("meet", "Google Meet usage metrics"));
        cols.add(new PredefinedColumnMetadata("meet.num_calls", "Number of Google Meet calls"));
        cols.add(new PredefinedColumnMetadata("meet.total_call_minutes", "Total call minutes for Google Meet"));
        cols.add(new PredefinedColumnMetadata("meet.num_1day_active_users", "Users active on Meet in the last day"));
        cols.add(new PredefinedColumnMetadata("meet.num_30day_active_users", "Users active on Meet in the last 30 days"));

        return cols;
    }
}
