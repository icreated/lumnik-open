import { useState, useEffect } from './html.mjs';

// Live matchMedia, read synchronously on first render so the wrong representation never mounts
// for a frame; follows resize/rotation afterwards. Shared by every screen that swaps a sheet for a
// panel (Fiches, Search).
export function useMatchMedia(query) {
  const [matches, setMatches] = useState(() => matchMedia(query).matches);
  useEffect(() => {
    const mq = matchMedia(query);
    setMatches(mq.matches);
    const onChange = e => setMatches(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, [query]);
  return matches;
}
