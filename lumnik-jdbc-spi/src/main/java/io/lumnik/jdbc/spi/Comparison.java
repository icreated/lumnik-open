package io.lumnik.jdbc.spi;

/** The two key comparisons the query builder asks a dialect to express. */
public enum Comparison {
  /** Rows strictly after the cursor: {@code (keys) > (cursor values)}. */
  GREATER_THAN,
  /** Rows up to and including the run's ceiling: {@code (keys) <= (ceiling values)}. */
  LESS_OR_EQUAL
}
