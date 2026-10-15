import { html, Fragment } from '../html.mjs';

// E4: bottom tab bar (phone/tablet) + left rail (display, ≥900px) — same items, same state, same
// markup; CSS alone decides which one is visible (styles.css @media rules). The items are the
// app's effective tabs (tabs.mjs) and `badges` maps a tab id to the count it wears: this component
// knows no tab by name.
export function TabBar({ items, tab, onTab, badges }) {
  const item = ({ id, icon, label }) => html`
    <button class=${tab === id ? 'active' : ''} onClick=${() => onTab(id)}>
      <span class="ico">${icon}</span>${label}
      ${badges[id] ? html`<span class="badge">${badges[id]}</span>` : null}
    </button>
  `;
  return html`
    <${Fragment}>
      <nav id="tabs">${items.map(item)}<//>
      <nav class="rail">${items.map(item)}<//>
    <//>
  `;
}
