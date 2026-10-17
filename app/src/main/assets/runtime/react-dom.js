// Anvex offline runtime: ESM wrapper over the bundled ReactDOM 18.3.1 UMD build.
const D = window.ReactDOM;
export default D;
export const createPortal = D.createPortal;
export const flushSync = D.flushSync;
export const version = D.version;
export const findDOMNode = D.findDOMNode;
export const unmountComponentAtNode = D.unmountComponentAtNode;
