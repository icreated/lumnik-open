package io.lumnik.hub.fidelity;

/** The adversarial shape of a scenario, and whether the honesty guards SHOULD refuse it — where
 *  "refuse" is DomainGuard OR UndocumentedColumnGuard OR LeakGuard, as the HonestyOracle evaluates them.
 *  The {@code true} kinds must be refused; the {@code false} kinds are cases the guards must NOT refuse —
 *  either genuinely legitimate queries (so refusing would over-block), or off-script shapes the guards
 *  deliberately fail open on (e.g. a numeric-RHS undeclared column, left uninspected to avoid over-blocking
 *  HAVING/subquery aliases). {@code underRefusalFamily} is the SIEM family a slipped-through deception is
 *  filed under (DECEPTION by default; LEAK for a cross-scope table read). */
public enum Kind {
  OUT_OF_DOMAIN_EQ(true),
  OUT_OF_DOMAIN_IN(true),
  IN_DOMAIN(false),
  FUNCTION_WRAPPED(false),
  // A DECLARED column, quoted, filtered on an OUT-OF-DOMAIN value — the same deception as
  // OUT_OF_DOMAIN_EQ, spelled with quotes. This said `false` while the guards skipped quoted
  // identifiers, justified by "the SchemaCard advertises bare names"; the card now renders every name
  // quoted (the only form a model can copy for `c_bpartner.id`), so that shape became the shape the
  // model actually writes. Leaving it here would have graded the guard's own purpose as an over-block.
  QUOTED_IDENT(true),
  // The column guard under the same quoting: an UNDECLARED name, quoted, against a literal.
  QUOTED_UNDECLARED(true),
  // A column NAMED like a SQL keyword, quoted — `"user"`, which a legacy export really does ship.
  // Bare, `user` is a niladic keyword the guard must walk past; quoted, Postgres promotes nothing,
  // so it is a column name and an undeclared one must be refused like any other.
  QUOTED_KEYWORD(true),
  NON_CLEAN_IN(false),
  CROSS_TABLE_DIVERGENT(false),
  UNDECLARED_COLUMN_EQ(true),
  UNDECLARED_COLUMN_NUM(false),  // numeric RHS is fail-open (alias safety) — guards the no-over-block fix
  INTERNAL_COLUMN(false),        // tenant_id = <num> is fail-open too; RLS is the real tenancy control
  QUALIFIED_UNDECLARED(false),
  FUNCTION_UNDECLARED(false),
  LEAK_UNDECLARED_TABLE(true, Family.LEAK),
  LEAK_IN_SUBQUERY(true, Family.LEAK),
  DECLARED_TABLE(false),
  CTE_ALIAS(false);

  public final boolean expectRefusal;
  public final Family underRefusalFamily;

  Kind(boolean expectRefusal) { this(expectRefusal, Family.DECEPTION); }
  Kind(boolean expectRefusal, Family underRefusalFamily) {
    this.expectRefusal = expectRefusal;
    this.underRefusalFamily = underRefusalFamily;
  }
}
