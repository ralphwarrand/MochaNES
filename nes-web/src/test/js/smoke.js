// Smoke test for the compiled web build.
//
// Runs the real mochanes.js against a minimal DOM, so faults that only appear
// after ahead-of-time compilation are caught. That is not hypothetical: the
// compiler renames script parameters to short names like `b` and `c`, and a
// local of the same name silently shadows them. Two shipped bugs came from
// exactly that - display scaling never applied, and fetched ROMs failed to load
// while picked ones worked - and neither showed up as a compile error.
//
//   node smoke.js <site-dir>
//
// Exits non-zero on the first failure.

const fs = require('fs');
const path = require('path');

const SITE = process.argv[2] || 'nes-web/target/site';
let failures = 0;

function check(name, condition, detail) {
  if (condition) {
    console.log('  ok    ' + name);
  } else {
    console.log('  FAIL  ' + name + (detail ? '  (' + detail + ')' : ''));
    failures++;
  }
}

// ---------------------------------------------------------------- DOM double

const imageData = { data: new Uint8ClampedArray(256 * 240 * 4) };
let presented = 0;
const ctx2d = {
  imageSmoothingEnabled: true,
  createImageData: () => imageData,
  putImageData: () => { presented++; },
};

function element(id, extra) {
  return Object.assign({
    id,
    textContent: '',
    style: {},
    listeners: {},
    getAttribute: function (a) { return this.attrs ? this.attrs[a] : null; },
    addEventListener: function (t, fn) { this.listeners[t] = fn; },
    // Only a 2D context is offered, so the WebGL fallback path is exercised.
    getContext: (kind) => (kind === '2d' ? ctx2d : null),
  }, extra || {});
}

const elements = {
  screen: element('screen'),
  status: element('status'),
  fps: element('fps'),
  regs: element('regs'),
  disasm: element('disasm'),
  memory: element('memory'),
  rom: element('rom'),
  demo: element('demo'),
  crtNote: element('crtNote'),
};
for (let i = 0; i < 8; i++) elements['key' + i] = element('key' + i);

const commandNodes = [
  element('n1', { tagName: 'BUTTON', attrs: { 'data-cmd': 'pause' } }),
  element('n2', { tagName: 'SELECT', attrs: { 'data-cmd': 'scale' } }),
];

let pendingFrame = null;
global.requestAnimationFrame = (cb) => { pendingFrame = cb; };
global.window = global;
global.location = { search: '' };
global.document = {
  getElementById: (id) => elements[id] || null,
  querySelectorAll: () => commandNodes,
  createElement: () => element('tmp'),
  addEventListener: () => {},
};
global.btoa = (s) => Buffer.from(s, 'binary').toString('base64');
global.URLSearchParams = class { get() { return null; } };
global.localStorage = {
  data: {},
  getItem(k) { return k in this.data ? this.data[k] : null; },
  setItem(k, v) { this.data[k] = String(v); },
};
global.navigator = { getGamepads: () => [] };

// A fetch double, so the URL-loading path is covered without a network.
const romBytes = fs.readFileSync(path.join(SITE, 'nestest.nes'));
global.fetch = (url) => Promise.resolve({
  ok: true,
  status: 200,
  arrayBuffer: () => Promise.resolve(
      romBytes.buffer.slice(romBytes.byteOffset, romBytes.byteOffset + romBytes.byteLength)),
});
global.FileReader = class {};

// -------------------------------------------------------------------- checks

const mod = require(path.resolve(SITE, 'mochanes.js'));

console.log('web build smoke test');
check('module exports main', typeof mod.main === 'function');
mod.main([], () => {});

const N = window.NESW;
check('platform object created', !!N);
check('renderer fell back to 2D', !!N.ctx2d, 'no WebGL in node, so 2D is expected');
check('ROM entry point registered', typeof N.loadRom === 'function');
check('URL loader registered', typeof N.loadUrl === 'function');
check('command router registered', typeof N.command === 'function');
check('frame stepper registered', typeof N.stepFrames === 'function');

// Direct load, then run.
N.loadRom('nestest.nes', romBytes.toString('base64'));
check('ROM loaded', /mapper 0/.test(elements.status.textContent), elements.status.textContent);

N.stepFrames(120);
let lit = 0;
for (let i = 0; i < N.pixels.length; i++) if ((N.pixels[i] & 0xFFFFFF) !== 0) lit++;
check('emulator drew a frame', lit > 5000, lit + ' lit pixels');

// Display scaling: regression guard for the shadowed `scale` parameter.
N.command('scale', '4');
check('4x scaling applies a width', elements.screen.style.width === '1024px',
      'width=' + elements.screen.style.width);
check('4x scaling applies a height', elements.screen.style.height === '768px',
      'height=' + elements.screen.style.height);
N.command('aspect', 'pixel');
N.command('scale', '2');
check('pixel-perfect 2x is 512x480',
      elements.screen.style.width === '512px' && elements.screen.style.height === '480px',
      elements.screen.style.width + 'x' + elements.screen.style.height);
N.command('scale', '0');
check('fit width restores a fluid size', /min\(/.test(elements.screen.style.width),
      elements.screen.style.width);

// Debugger output.
N.command('step', '');
check('registers reported', /PC [0-9A-F]{4}/.test(elements.regs.textContent),
      elements.regs.textContent.slice(0, 60));
check('disassembly produced', elements.disasm.textContent.length > 20);
check('memory dump produced', /^[0-9A-F]{4}  /.test(elements.memory.textContent));

// Settings persistence.
N.command('crt', '1');
check('settings persisted', localStorage.getItem('mochanes.crt') === '1');

// URL loading: regression guard for the shadowed ROM handler.
elements.status.textContent = '';
N.loadUrl('nestest.nes', 'fetched.nes');
setTimeout(() => {
  check('fetched ROM loaded', /mapper 0/.test(elements.status.textContent),
        elements.status.textContent || '(no status)');
  console.log(failures === 0 ? '\nall checks passed' : '\n' + failures + ' check(s) failed');
  process.exit(failures === 0 ? 0 : 1);
}, 50);
