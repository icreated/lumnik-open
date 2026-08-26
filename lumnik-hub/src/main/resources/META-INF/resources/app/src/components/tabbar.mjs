import { html, Fragment } from '../html.mjs';

// E4: bottom tab bar (phone/tablet) + left rail (display, ≥900px) — same three items, same
// state, same markup; CSS alone decides which one is visible (styles.css @media rules).
const ITEMS = [
  { tab: 'fiches', icon: '▦', label: 'Fiches' },
  { tab: 'inbox', icon: '⚡', label: 'Décisions' },
  { tab: 'chat', icon: '✶', label: 'Chat' },
];

export function TabBar({ tab, onTab, badge }) {
  const item = ({ tab: t, icon, label }) => html`
    <button class=${tab === t ? 'active' : ''} onClick=${() => onTab(t)}>
      <span class="ico">${icon}</span>${label}
      ${t === 'inbox' && badge ? html`<span class="badge">${badge}</span>` : null}
    </button>
  `;
  return html`
    <${Fragment}>
      <nav id="tabs">${ITEMS.map(item)}<//>
      <nav class="rail">${ITEMS.map(item)}<//>
    <//>
  `;
}
