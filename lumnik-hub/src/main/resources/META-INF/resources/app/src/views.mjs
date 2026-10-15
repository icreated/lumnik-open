// View discovery + rendering for the real PWA at /app/ — ported from the spike's
// discoverView()/renderViews()/openDetail() (m/index.html, ~L388-L459).
// B5: filters are passed through raw, one `filter=` query param per string, encoded — no
// client-side validation. The hub is the one honest referee for what a filter means; this
// module never second-guesses it.

export function createViews(api, scope) {
  function list() {
    return api.json(`/api/views?scope=${encodeURIComponent(scope)}`, { method: 'GET' });
  }

  function yaml(name) {
    return api.text(`/api/views/${encodeURIComponent(name)}`, 'application/yaml');
  }

  // `page` ({pageSize, cursor}) opts into the hub's paged read; the cursor is opaque and sent back
  // exactly as issued. Without it the hub answers its unpaged, capped read.
  function render(yaml, filters = [], { pageSize, cursor } = {}) {
    const parts = filters.map(f => 'filter=' + encodeURIComponent(f));
    if (pageSize != null) parts.push('pageSize=' + encodeURIComponent(pageSize));
    if (cursor != null) parts.push('cursor=' + encodeURIComponent(cursor));
    const qs = parts.length ? '?' + parts.join('&') : '';
    return api.json('/api/views/render' + qs, { yaml });
  }

  function detail(yaml, key) {
    return api.json(`/api/views/detail?key=${encodeURIComponent(key)}`, { yaml });
  }

  return { list, yaml, render, detail, scope };
}
