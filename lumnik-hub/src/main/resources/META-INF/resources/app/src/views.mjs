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

  function render(yaml, filters = []) {
    const qs = filters.length ? '?' + filters.map(f => 'filter=' + encodeURIComponent(f)).join('&') : '';
    return api.json('/api/views/render' + qs, { yaml });
  }

  function detail(yaml, key) {
    return api.json(`/api/views/detail?key=${encodeURIComponent(key)}`, { yaml });
  }

  return { list, yaml, render, detail, scope };
}
