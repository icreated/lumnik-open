// Typed filter building. The hub stays the final authority (ViewFilter.java): this module only
// composes the raw grammar string (`col=val`, `col~text`, `col>=v`…) from a column, an operator
// the hub announced for it, and a value — it never validates a value and never reinterprets a
// refusal. The grammar string is what crosses the API boundary, unchanged.
const WORDS = { '=': '=', '~': 'contient', '>=': '≥', '<=': '≤', '>': '>', '<': '<' };
// Two-character operators first, so `amount>=10` is never read as `amount>` + `=10`.
const OPS = Object.keys(WORDS).sort((a, b) => b.length - a.length);
const INPUT_TYPES = { choice: 'select', text: 'text', number: 'number', date: 'date', datetime: 'datetime-local' };
// What a column accepts is the hub's call (ColumnKind.java): its `kind` and `operators` ride on
// every resolved column. This module only presents them. A response without them (an old hub)
// degrades to a plain text column.
const FALLBACK_OPS = ['~', '='];

// choice | number | date | datetime | text
export function kindOf(column) {
  return column.kind || 'text';
}

const offeredBy = column => (column.operators && column.operators.length ? column.operators : FALLBACK_OPS);

export function operatorsFor(column) {
  return offeredBy(column).map(op => ({ op, label: WORDS[op] }));
}

// The operator in force: the chosen one while the column still offers it, else its first.
// A stale choice (another column, another view) must never build a filter the hub refuses.
export function resolveOp(column, op) {
  const offered = offeredBy(column);
  return offered.includes(op) ? op : offered[0];
}

export function inputTypeFor(kind) {
  return INPUT_TYPES[kind] || 'text';   // a kind from a newer hub degrades to a text box
}

// null when there is nothing to filter on (blank value). A value is passed verbatim after trim.
export function buildFilter(column, op, value) {
  const v = String(value ?? '').trim();
  return v ? column.name + op + v : null;
}

// Display only: the raw string is what is stored and sent. Anything not recognised stays raw.
export function chipLabel(raw, columns) {
  for (const c of columns) {
    if (!raw.startsWith(c.name)) continue;
    const rest = raw.slice(c.name.length);
    const op = OPS.find(o => rest.startsWith(o));
    if (op) return `${c.label || c.name} ${WORDS[op]} ${rest.slice(op.length)}`;
  }
  return raw;
}
