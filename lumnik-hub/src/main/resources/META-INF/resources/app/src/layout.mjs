// Responsive matrix (design doc F), as data. The breakpoints mirror the @media rules in
// styles.css — a test pins them together. A viewport shows exactly one row representation
// (phone = cards, tablet+ = table), so only that one is ever mounted.
export const ROW_QUERY = '(min-width: 640px)';
export const WIDE_QUERY = '(min-width: 900px)';

export function rowRepresentation(tabletUp) {
  return tabletUp ? 'table' : 'cards';
}
