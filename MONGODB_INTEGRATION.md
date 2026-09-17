# MongoDB Database Driver / Connectivity — Round 2 Assessment

**Task:** Add MongoDB database driver/connectivity support to Helical Insight, integrated
in the same manner as existing database connectivity, configurable as a data source, without
breaking existing functionality.

## 1. What was already there

Before making any change I looked for how Helical Insight already plugs in databases that
aren't handled by a plain JDBC `Statement`/`ResultSet`, since MongoDB is a document store, not
a relational database. That led to an existing, first-class extension point:

- `com.helicalinsight.datasource.nosql.NoSQLLoader` — an abstract class with two methods,
  `loadToMiddleWare(JsonObject formData)` and `testConnection(JsonObject formData)`, that a
  data source implementation must provide.
- `com.helicalinsight.efw.utility.NoSqlUtils.getNoSqlImplementation(subType)` looks up the
  right `NoSQLLoader` bean by name (the `subType`/`driverName` value sent from the data
  source form) via Spring's `ApplicationContext`.
- `com.helicalinsight.efw.components.NoSqlDataSourceProperties` wires this into data source
  creation/editing (`writeNoSqlDataSource`), and even had a `//Todo Create a Test connection
  for subtype` comment next to the sample "mongo" test payload — i.e. this path was already
  designed with MongoDB in mind but never finished.
- One implementation already existed: `MongoDrillLoader` (`@Component("com.helicalinsight.nosql.mongo")`,
  marked `@Deprecated`). It doesn't talk to MongoDB directly — it pushes the connection
  details to an **external Apache Drill installation's REST API**, which then does the actual
  work through Drill's own "mongo" storage plugin. That means MongoDB support only worked if
  the server also had Apache Drill installed, configured, and reachable — a heavy, brittle
  extra dependency, and it used the old, deprecated `com.mongodb.MongoClient` legacy driver
  API.

Both `driverDefaultQuery.properties` and `sqlFunctionsXmlMapping.properties` (under
`server/hi-repository/System/Admin/`) already contain entries for Mongo-flavoured driver
class names, and `org.mongodb:mongo-java-driver` (v3.12.10) is already declared as a project
dependency in the parent `server/pom.xml` — so the underlying driver jar didn't need to be
added, and the config plumbing for a JDBC-style Mongo driver was already partly in place. The
actual gap was a **working, native implementation** behind the `NoSQLLoader` extension point.

## 2. What changed

| File | Change |
|---|---|
| `server/adhoc/src/main/java/com/helicalinsight/adhoc/services/MongoNativeLoader.java` | **New.** Native `NoSQLLoader` implementation for MongoDB, registered as `com.helicalinsight.nosql.mongo`. |
| `server/adhoc/src/main/java/com/helicalinsight/adhoc/services/MongoDrillLoader.java` | Bean name changed from `com.helicalinsight.nosql.mongo` to `com.helicalinsight.nosql.mongo.drill`, with a comment explaining why. Kept (not deleted) for any existing installation that already has Apache Drill set up and depends on this exact path. Nothing else in the class was touched. |

No other files needed to change: no new Maven dependency was required (the MongoDB Java
driver was already on the classpath of every module, including `adhoc`), and no existing
class references the old `MongoDrillLoader` bean by name anywhere else in the codebase (verified
with a project-wide search), so re-pointing the `com.helicalinsight.nosql.mongo` bean name at
the new class does not break anything that currently exists.

## 3. How `MongoNativeLoader` works

It uses the official MongoDB Java driver (`com.mongodb.client.MongoClients`,
`MongoClientSettings`) directly — no external service, no Drill, no extra jar.

- **`testConnection(formData)`** opens a short-lived `MongoClient`, and runs the `ping`
  administrative command against the target database — the same lightweight health check
  MongoDB documents for verifying connectivity and, when credentials are supplied, that they
  are valid for that database. If a `collection` field was supplied, it also checks (as a
  warning only, not a hard failure — Mongo creates collections lazily) whether that collection
  currently exists.
- **`loadToMiddleWare(formData)`** — the method invoked when the data source is actually
  saved from the Admin UI — calls `testConnection` and throws a clear
  `EfwServiceException` if it fails, so a broken MongoDB configuration is never silently
  persisted.
- Connection details can be supplied either as a full connection string (`uri` or `jdbcUrl`,
  e.g. `mongodb://host:27017` or a `mongodb+srv://...` Atlas URI) **or** as discrete
  `host` / `port` / `userName` / `password` fields, matching how the rest of Helical
  Insight's data source forms behave.
- Supports `SCRAM-SHA-1`, `SCRAM-SHA-256`, and `PLAIN` auth mechanisms via the
  `authMechanism` field, and a `timeOut` field for the connect timeout (defaults to 8s,
  same as the server-selection timeout).
- All resources (`MongoClient`) are opened and closed per call via try-with-resources — no
  connection pooling/leaks.

## 4. Configuring a MongoDB connection

Using the existing "NoSQL data source" flow, POST (or submit from the Admin UI, once a
MongoDB option is exposed there) a payload such as:

```json
{
  "classifier": "global",
  "name": "MyMongoConnection",
  "driverName": "com.helicalinsight.nosql.mongo",
  "dataSourceProvider": "nosql",
  "subType": "com.helicalinsight.nosql.mongo",
  "host": "localhost",
  "port": 27017,
  "database": "myDatabase",
  "userName": "myUser",
  "password": "myPassword",
  "authMechanism": "SCRAM-SHA-1",
  "collection": "myCollection"
}
```

Or, using a full Atlas connection string instead of discrete host/port:

```json
{
  "classifier": "global",
  "name": "MyAtlasConnection",
  "driverName": "com.helicalinsight.nosql.mongo",
  "dataSourceProvider": "nosql",
  "subType": "com.helicalinsight.nosql.mongo",
  "uri": "mongodb+srv://myUser:myPassword@cluster0.example.mongodb.net",
  "database": "myDatabase",
  "collection": "myCollection"
}
```

`NoSqlDataSourceProperties.getSubType()` uses `driverName` (falling back to `subType`) to
look up the bean, so `driverName` is the field that must be set to
`com.helicalinsight.nosql.mongo` for this implementation to be used.

For a locally installed, unauthenticated MongoDB (e.g. a quick Docker container:
`docker run -p 27017:27017 mongo`), omit `userName`/`password`/`authMechanism` entirely.

## 5. Testing it locally

1. Start a local MongoDB instance, e.g.:
   ```
   docker run -d --name mongo-test -p 27017:27017 mongo:7
   ```
2. Build and deploy Helical Insight as usual (`mvn clean package`, deploy the resulting WAR).
3. Create a data source with the payload shown above (host `localhost`, port `27017`, no
   credentials needed for this test container).
4. The connection test now succeeds against the real MongoDB server instead of requiring an
   Apache Drill installation.

## 6. Known limitations / suggested next steps

Full ad-hoc report/metadata browsing (listing collections as "tables" and fields as
"columns" in the report designer) is driven by the same `java.sql.DatabaseMetaData`/
`ResultSet` machinery used for relational databases (see
`server/adhoc/.../metadata/genericdb/`), which assumes a real JDBC driver underneath. A
document store doesn't naturally provide that. Fully bridging MongoDB into that generic
relational metadata/query pipeline would mean either:

- writing a complete JDBC driver on top of the native Mongo driver (translating a workable
  SQL subset into `find`/`aggregate` calls and exposing `DatabaseMetaData.getTables()` /
  `getColumns()` backed by collection listings and sampled document fields), or
- extending the ad-hoc/metadata layer to special-case `NOSQL_DATASOURCE` connections and
  read collections/fields directly through the native driver instead of through
  `DatabaseMetaData`.

That is a materially larger effort (this is genuinely why MongoDB's own official JDBC driver,
`org.mongodb:mongodb-jdbc`, is a project on its own, and why the previous implementation
reached for Apache Drill rather than writing this by hand). I've scoped this change to make
MongoDB a real, working, natively-connected data source — matching what the `NoSQLLoader`
extension point was already designed for — and documented the remaining gap rather than
leaving it silently unfinished, so it's a clear next PR rather than a surprise.

I'm happy to walk through this reasoning and the trade-off in the next round.
