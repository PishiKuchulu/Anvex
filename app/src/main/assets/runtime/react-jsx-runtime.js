// Anvex offline runtime: react/jsx-runtime + react/jsx-dev-runtime wrapper
// (used when Babel transpiles with the automatic JSX runtime, or when a module
// imports these specifiers explicitly).
// jsx(type, props, key) — the automatic-runtime signature. React 18's
// createElement(type, config) reads `key` and `children` out of config, so the
// third positional argument must be folded into the config, NOT passed as a
// child (that was the v1.1.0 wrapper bug).
const R = window.React;
function jsxImpl(type, props, key) {
  var config = {};
  for (var k in props) { config[k] = props[k]; }
  if (key !== undefined) config.key = key;
  return R.createElement(type, config);
}
export const jsx = jsxImpl;
export const jsxs = jsxImpl;
export const jsxDEV = jsxImpl;
export const Fragment = R.Fragment;
