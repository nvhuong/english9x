// Runs before first paint: apply the saved theme (light | dark | system) to <html data-theme>. Keep in sync with src/design/theme.ts.
(function () {
  var mode = 'system';
  try { mode = localStorage.getItem('e9x.theme') || 'system'; } catch (e) { /* storage blocked: fall back to system */ }
  var dark = mode === 'dark' || (mode === 'system' && window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
})();
