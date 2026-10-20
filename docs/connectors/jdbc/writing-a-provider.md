# Writing a JDBC provider

> `jdbc-generic` reads one database per connector. The database it speaks is decided by a
> **provider**: a module that owns that database's SQL dialect and its JDBC driver. PostgreSQL
> ships with every edition, Oracle with lumnik Pro; any other database is one more provider.

This page is for a Java developer adding a database. Using an installed provider needs no
code — see [JDBC → Connection](../jdbc.md#connection).

## What a provider is

- A Maven module `io.lumnik:lumnik-jdbc-<db>`, classes under the package `io.lumnik.jdbc.<db>`.
- It depends on `lumnik-jdbc-spi` and on **its own driver**, even when the hub already carries it.
- It exposes one CDI bean implementing `JdbcDialect`. The hub never imports a provider class; it
  collects every installed bean.

Two build details decide whether the hub sees the provider at all:

- the module builds a **jandex index** (`jandex-maven-plugin`) — without it the hub boots with that
  dialect missing, and says nothing;
- the hub depends on it with `runtime` scope.

Providers add up: several live in one image, and each connector picks its own.

## What each method owes

| Method | Contract |
|---|---|
| `id()` | The lowercase name used in `dialect:`. Unique across installed providers. |
| `aliases()` | Other accepted spellings (PostgreSQL: `postgresql`). Unique too. |
| `acceptsUrl(url)` | Whether this provider recognises the JDBC scheme. Used to infer a missing `dialect:`. |
| `driverClass()` | The driver the pool loads. A provider installed without its driver fails with `JDBC driver 'X' of dialect 'Y' is not on the classpath`. |
| `quoteIdentifier(id)` | Quote one identifier. The query builder never concatenates a raw name. |
| `keyComparison(columns, comparison)` | The `>` (cursor) or `<=` (run ceiling) comparison over the quoted key columns, **and the bind plan**. No default. |
| `limitClause(n)` | The row limit appended after `ORDER BY` (`LIMIT 100`, `FETCH FIRST 100 ROWS ONLY`, …). No default. |
| `defaultSkipPatterns()` | Table-name globs excluded from discovery when the request gives none. |
| `unsupportedReason(column)` | Why this provider cannot read a column, or empty. Discovery lists the table with the column named; the guided add refuses it. Default: every column is readable. |
| `readColumn(rs, index)` | The value of a cell. Default `getObject`; override for a LOB locator or a driver-specific timestamp class, and throw to refuse a type you cannot read. |
| `isWallClock(metadata, index)` | Whether the column holds a date and time written with no time zone, which the hub then reads in the connection's `zone`, UTC without one. Default: the column's JDBC type is `TIMESTAMP`. Override it when your driver reports a zoned type under that same code. |
| `sessionInitSql()` | A statement run on every new connection of this dialect's pool — for a database whose session settings (sort order, number format) change what a query means. Default: none. |
| introspection | `listTables`, `tableExists`, `listColumns`, `listPrimaryKey`, `listForeignKeys`, `estimateRowCount`, `inferWatermarkColumn`. |

`keyComparison` and `limitClause` have no default so that a provider cannot compile while emitting
SQL its database rejects, or reads differently.

### The bind plan

`keyComparison` returns a `KeyComparison(sql, parameterIndexes)`. The `i`-th `?` of the SQL takes the
value of key column `parameterIndexes[i]`.

| Dialect | SQL for `(a, b) > cursor` | Plan |
|---|---|---|
| PostgreSQL (row value) | `("a", "b") > (?, ?)` | `[0, 1]` |
| a database without tuple comparison | `(("a" > ?) OR ("a" = ? AND "b" > ?))` | `[0, 0, 1]` |

- The SQL is one self-contained, parenthesised predicate: the builder joins it with `AND`, and for a
  watermark ceiling with `OR <watermark> IS NULL`.
- The builder refuses a plan that is empty for a non-empty key, or that names a column that does not
  exist.

## How a connector picks its provider

| Manifest | Result |
|---|---|
| `dialect:` names an installed provider, URL accepted | that provider |
| `dialect:` names a provider that is not installed | refused: `JDBC dialect 'X' is not installed. Available: …` |
| `dialect:` set, URL not accepted by it | refused, naming the dialect and the URL scheme |
| `dialect:` absent, exactly one provider accepts the URL | that provider |
| `dialect:` absent, none accepts it | refused, listing the installed providers |
| `dialect:` absent, several accept it | refused as ambiguous |

- Names are compared trimmed and lowercased.
- Two providers claiming the same id or alias stop the hub at startup, naming both.
- A connection pool is kept per URL, user, tenant, driver **and dialect**: two providers that accept the
  same URL never share one.
