# Writing a JDBC provider

> `jdbc-generic` reads one database per connector. Which database it speaks is decided by a
> **provider**: a small module that owns that database's SQL dialect and its JDBC driver. PostgreSQL
> ships with the hub; any other database is one more provider.

!!! note "The contract is young"
    `lumnik-jdbc-spi` has been exercised by one provider so far. Expect it to move when the second
    one (Oracle) lands, and treat it as stable only after that.

## What a provider is

A provider is a Maven module, `io.lumnik:lumnik-jdbc-<db>`, with its classes under the Java package
`io.lumnik.jdbc.<db>`. It depends on `lumnik-jdbc-spi` and on **its own driver** (even when the hub
already carries it), and exposes one CDI bean implementing `JdbcDialect`. The hub never imports a
provider class: it reaches them through `JdbcDialectRegistry`, which collects every installed bean.
`scripts/check-bounded-contexts.sh` enforces that boundary.

Two build details decide whether the provider is seen at all:

- the module builds a **jandex index** (`jandex-maven-plugin`), otherwise Quarkus does not discover
  its bean and the hub boots with that dialect silently missing;
- the hub depends on it with `runtime` scope.

Providers are cumulative: unlike the Search providers, several of them live in one image and each
connector picks its own.

## What each method owes

| Method | Contract |
|---|---|
| `id()` | The lowercase name used in `dialect:`. Unique across installed providers. |
| `aliases()` | Other accepted spellings (PostgreSQL: `postgresql`). Unique too. |
| `acceptsUrl(url)` | Whether this provider recognises the JDBC scheme. Used to infer a missing `dialect:`. |
| `driverClass()` | The driver the pool loads explicitly. A provider installed without its driver fails with `JDBC driver 'X' of dialect 'Y' is not on the classpath`. |
| `quoteIdentifier(id)` | Quote one identifier. The query builder never concatenates a raw name. |
| `keyComparison(columns, comparison)` | The `>` (cursor) or `<=` (run ceiling) comparison over the quoted key columns, **and the bind plan**. |
| `limitClause(n)` | The row limit appended after `ORDER BY` (`LIMIT 100`, `FETCH FIRST 100 ROWS ONLY`, …). |
| `defaultSkipPatterns()` | Table-name globs excluded from discovery when the request gives none. |
| `unsupportedReason(column)` | Why this provider cannot read a column, or empty. Discovery lists the table with the column named; the guided add refuses it. Default: every column is readable. |
| `readColumn(rs, index)` | The value of a cell. Default `getObject`; override for a LOB locator or a driver-specific timestamp class, and refuse a type you cannot read by throwing. |
| `sessionInitSql()` | A statement run on every new connection of this dialect's pool — for a database whose session settings (sort order, number format taken from the client's locale) change what a query means. Default: none. |
| introspection | `listTables`, `tableExists`, `listColumns`, `listPrimaryKey`, `listForeignKeys`, `estimateRowCount`, `inferWatermarkColumn`. |

`keyComparison` and `limitClause` have **no default**, on purpose. A default row-value tuple plus
`LIMIT` would let a provider compile while emitting SQL its database rejects — or reads differently,
which silently drops rows with no dead letter. Every provider states both.

### The bind plan

`keyComparison` returns a `KeyComparison(sql, parameterIndexes)`. The `i`-th `?` of the SQL takes the
value of key column `parameterIndexes[i]`. The reader binds through that plan; it never guesses.

| Dialect | SQL for `(a, b) > cursor` | Plan |
|---|---|---|
| PostgreSQL (row value) | `("a", "b") > (?, ?)` | `[0, 1]` |
| a database without tuple comparison | `(("a" > ?) OR ("a" = ? AND "b" > ?))` | `[0, 0, 1]` |

The SQL must be one self-contained, parenthesised predicate: the builder joins it with `AND` and, for
a watermark ceiling, with `OR <watermark> IS NULL`. The builder refuses a plan that is empty for a
non-empty key, or that names a column index that does not exist.

## How a connector picks its provider

| Manifest | Result |
|---|---|
| `dialect:` names an installed provider, URL accepted | that provider |
| `dialect:` names a provider that is not installed | refused: `JDBC dialect 'X' is not installed. Available: …` |
| `dialect:` set, URL not accepted by it | refused, naming the dialect and the URL scheme |
| `dialect:` absent, exactly one provider accepts the URL | that provider |
| `dialect:` absent, none accepts it | refused, listing the installed providers |
| `dialect:` absent, several accept it | refused as ambiguous — there is no first-wins |

Names are compared after `trim()` and lowercasing. Two providers claiming the same id or alias stop
the hub at startup, naming both.

A pool is cached per URL, user, tenant, driver **and dialect id**: the provider decides what runs on a new
connection, so two providers that accept the same URL never share one.
