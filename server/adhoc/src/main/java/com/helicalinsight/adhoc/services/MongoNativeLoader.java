package com.helicalinsight.adhoc.services;

import com.google.gson.JsonObject;
import com.helicalinsight.datasource.GsonUtility;
import com.helicalinsight.datasource.nosql.NoSQLLoader;
import com.helicalinsight.efw.exceptions.EfwServiceException;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Native MongoDB connectivity for Helical Insight's NoSQL data source framework.
 * <p>
 * This is a direct replacement for {@link MongoDrillLoader}, which proxied every MongoDB
 * connection through an external Apache Drill installation and its "mongo" storage plugin.
 * That extra hop meant MongoDB support only worked when Drill was separately installed and
 * configured, and it depended on the legacy {@code com.mongodb.MongoClient} driver API.
 * <p>
 * This implementation talks to MongoDB directly using the official MongoDB Java driver
 * (`org.mongodb:mongo-java-driver`), which is already a Helical Insight dependency, so no
 * additional server-side component needs to be installed.
 * <p>
 * It is registered under the same Spring bean name the framework already reserves for
 * Mongo ({@code com.helicalinsight.nosql.mongo}), so any data source whose
 * {@code subType}/{@code driverName} is set to that value (see
 * {@code NoSqlDataSourceProperties#getSubType(JsonObject)}) now gets a fully working,
 * native connection instead of the deprecated Drill bridge.
 *
 * @author Navadeep
 */
@Component("com.helicalinsight.nosql.mongo")
@Scope("prototype")
public class MongoNativeLoader extends NoSQLLoader {

    private static final Logger logger = LoggerFactory.getLogger(MongoNativeLoader.class);

    private static final int DEFAULT_PORT = 27017;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 8000;
    private static final int DEFAULT_SERVER_SELECTION_TIMEOUT_MS = 8000;

    /**
     * Invoked by {@code NoSqlDataSourceProperties}/{@code NoSqlDataSourcePropertiesDB} while a
     * MongoDB data source is being created or updated from the Admin UI. The connection details
     * are verified up front so that a broken configuration is never silently persisted.
     *
     * @param formData the data source form, expected to contain the same fields understood by
     *                 {@link #testConnection(JsonObject)}
     * @return {@code true} once connectivity has been verified
     * @throws EfwServiceException if MongoDB could not be reached with the supplied details
     */
    @Override
    public boolean loadToMiddleWare(JsonObject formData) {
        if (!testConnection(formData)) {
            throw new EfwServiceException("Could not connect to MongoDB with the supplied details. "
                    + "Please verify the host, port, database name and credentials and try again.");
        }
        return true;
    }

    /**
     * Opens a short-lived connection to MongoDB and verifies it with the {@code ping}
     * administrative command - the lightweight health check MongoDB itself recommends for
     * connection tests. If a {@code collection} was supplied, its presence in the target
     * database is also checked, purely as a helpful warning (a missing collection does not by
     * itself fail the connection test, since MongoDB creates collections lazily).
     *
     * <p>Supported form fields:</p>
     * <ul>
     *     <li>{@code uri} or {@code jdbcUrl} - a full MongoDB connection string
     *     ({@code mongodb://...} or {@code mongodb+srv://...}). When present, this takes
     *     precedence and {@code host}/{@code port}/{@code userName}/{@code password} are
     *     ignored (they are expected to already be encoded in the URI).</li>
     *     <li>{@code host} - host name, optionally as {@code host:port}</li>
     *     <li>{@code port} - port number, used only when not already part of {@code host}
     *     (defaults to {@value #DEFAULT_PORT})</li>
     *     <li>{@code userName}, {@code password} - credentials (optional, for unauthenticated
     *     deployments)</li>
     *     <li>{@code database} or {@code databaseName} - the database to authenticate/connect
     *     against (defaults to {@code admin})</li>
     *     <li>{@code authMechanism} - one of {@code SCRAM-SHA-1}, {@code SCRAM-SHA-256},
     *     {@code PLAIN}; defaults to MongoDB's negotiated default when omitted</li>
     *     <li>{@code timeOut} - connect timeout in milliseconds (optional)</li>
     *     <li>{@code collection} - collection name to sanity-check (optional)</li>
     * </ul>
     *
     * @param formData the data source form
     * @return {@code true} if the {@code ping} command succeeded
     * @throws EfwServiceException wrapping any connection/authentication failure with a
     *                             user-friendly message
     */
    @Override
    public boolean testConnection(JsonObject formData) {
        try (MongoClient mongoClient = buildClient(formData)) {
            String database = resolveDatabase(formData);
            MongoDatabase mongoDatabase = mongoClient.getDatabase(database);

            Document pingResult = mongoDatabase.runCommand(new Document("ping", 1));

            String collection = GsonUtility.optString(formData, "collection");
            if (StringUtils.isNotBlank(collection) && !collectionExists(mongoDatabase, collection)) {
                logger.warn("MongoDB connection succeeded but collection '{}' was not found in database '{}'. "
                        + "It will be created automatically the first time data is written to it.", collection, database);
            }

            return pingResult.get("ok") != null;
        } catch (MongoTimeoutException e) {
            logger.error("Timed out connecting to MongoDB", e);
            throw new EfwServiceException("Timed out while connecting to MongoDB. Please check the host/port "
                    + "and make sure the server is reachable from the Helical Insight server, "
                    + "and that the IP is whitelisted if you are using MongoDB Atlas.");
        } catch (MongoException e) {
            logger.error("MongoDB connection/authentication error", e);
            throw new EfwServiceException("Could not connect to MongoDB: " + e.getMessage());
        }
    }

    private boolean collectionExists(MongoDatabase mongoDatabase, String collection) {
        for (String name : mongoDatabase.listCollectionNames()) {
            if (name.equals(collection)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a short-lived {@link MongoClient} from the generic NoSQL data source form.
     */
    private MongoClient buildClient(JsonObject formData) {
        String uri = firstNonBlank(
                GsonUtility.optString(formData, "uri"),
                GsonUtility.optString(formData, "jdbcUrl"));

        int timeout = GsonUtility.optInt(formData, "timeOut");
        final int connectTimeoutMs = timeout > 0 ? timeout : DEFAULT_CONNECT_TIMEOUT_MS;

        if (StringUtils.isNotBlank(uri) && (uri.startsWith("mongodb://") || uri.startsWith("mongodb+srv://"))) {
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(uri))
                    .applyToSocketSettings(b -> b.connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS))
                    .applyToClusterSettings(b -> b.serverSelectionTimeout(DEFAULT_SERVER_SELECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    .build();
            return MongoClients.create(settings);
        }

        String hostField = firstNonBlank(GsonUtility.optString(formData, "host"), "localhost");
        String hostName = hostField;
        int port = DEFAULT_PORT;
        if (hostField.contains(":")) {
            String[] parts = hostField.split(":", 2);
            hostName = parts[0];
            port = parseIntOrDefault(parts[1], DEFAULT_PORT);
        } else {
            int formPort = GsonUtility.optInt(formData, "port");
            if (formPort > 0) {
                port = formPort;
            }
        }

        final ServerAddress serverAddress = new ServerAddress(hostName, port);
        String database = resolveDatabase(formData);
        String userName = GsonUtility.optString(formData, "userName");
        String password = GsonUtility.optString(formData, "password");

        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyToClusterSettings(b -> b.hosts(Collections.singletonList(serverAddress)))
                .applyToSocketSettings(b -> b.connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(DEFAULT_SERVER_SELECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        if (StringUtils.isNotBlank(userName)) {
            String authMechanism = GsonUtility.optString(formData, "authMechanism");
            builder.credential(buildCredential(authMechanism, userName, password, database));
        }

        return MongoClients.create(builder.build());
    }

    private MongoCredential buildCredential(String authMechanism, String userName, String password, String database) {
        char[] passwordChars = password != null ? password.toCharArray() : new char[0];
        if (StringUtils.isBlank(authMechanism)) {
            return MongoCredential.createCredential(userName, database, passwordChars);
        }
        switch (authMechanism.trim().toUpperCase()) {
            case "SCRAM-SHA-1":
            case "SCRAMSHA1":
                return MongoCredential.createScramSha1Credential(userName, database, passwordChars);
            case "SCRAM-SHA-256":
            case "SCRAMSHA256":
                return MongoCredential.createScramSha256Credential(userName, database, passwordChars);
            case "PLAIN":
                return MongoCredential.createPlainCredential(userName, database, passwordChars);
            default:
                return MongoCredential.createCredential(userName, database, passwordChars);
        }
    }

    private String resolveDatabase(JsonObject formData) {
        String database = firstNonBlank(
                GsonUtility.optString(formData, "database"),
                GsonUtility.optString(formData, "databaseName"));
        return StringUtils.isNotBlank(database) ? database : "admin";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
