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
  touchpad: element('touchpad', { hidden: true }),
  stage: element('stage'),
  memRange: element('memRange'),
  dbg: element('dbg', { open: false }),
  crtNote: element('crtNote'),
};
for (let i = 0; i < 8; i++) elements['key' + i] = element('key' + i);

const commandNodes = [
  element('n1', { tagName: 'BUTTON', attrs: { 'data-cmd': 'pause' } }),
  element('n2', { tagName: 'SELECT', attrs: { 'data-cmd': 'scale' } }),
];

// On-screen pad buttons, in controller order.
const touchNodes = [0, 1, 2, 3, 4, 5, 6, 7].map((i) =>
  Object.assign(element('btn' + i, { attrs: { 'data-btn': String(i) } }), {
    classList: { add() {}, remove() {} },
  }));

let pendingFrame = null;
global.requestAnimationFrame = (cb) => { pendingFrame = cb; };
// `performance`, like `navigator`, is read-only in recent Node, so a plain
// assignment is ignored and the pacing checks would silently measure real time.
let fakeClock = 0;
Object.defineProperty(global, 'performance', {
  value: { now: () => fakeClock },
  configurable: true,
  writable: true,
});
global.window = global;
global.location = { search: '' };
global.document = {
  getElementById: (id) => elements[id] || null,
  querySelectorAll: (sel) => (sel === '[data-btn]' ? touchNodes : commandNodes),
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
// Recent Node versions ship a read-only `navigator`, so a plain assignment is
// silently ignored and the touch detection never sees maxTouchPoints.
Object.defineProperty(global, 'navigator', {
  value: { getGamepads: () => [], maxTouchPoints: 1 },
  configurable: true,
  writable: true,
});
global.window.innerWidth = 1920;
global.window.innerHeight = 1080;
global.window.addEventListener = () => {};

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

// Touch controls.
check('touch buttons were bound', typeof touchNodes[0].listeners.pointerdown === 'function');
let touchThrew = null;
try {
  const fake = { preventDefault() {}, pointerId: 1 };
  touchNodes[0].listeners.pointerdown(fake);   // press A
  touchNodes[0].listeners.pointerup(fake);     // release A
} catch (e) { touchThrew = e; }
check('touch press and release work', touchThrew === null, String(touchThrew));
check('on-screen pad revealed on a touch device', elements.touchpad.hidden === false);

// Fullscreen sizing: regression guard for inline styles pinning the canvas to
// its windowed size, which left fullscreen letterboxed with huge margins.
N.command('scale', '0');
global.document.fullscreenElement = elements.stage;
N.applyDisplay();
check('fullscreen fills the viewport height',
      elements.screen.style.height === '100vh' || elements.screen.style.width === '100vw',
      'w=' + elements.screen.style.width + ' h=' + elements.screen.style.height);
check('fullscreen is not pinned to the windowed width',
      !/min\(/.test(elements.screen.style.width), elements.screen.style.width);
global.document.fullscreenElement = null;
N.applyDisplay();
check('leaving fullscreen restores the windowed size', /min\(/.test(elements.screen.style.width),
      elements.screen.style.width);

// Pacing: the loop must follow real time, not the animation-frame rate, or the
// console runs at display speed - nearly 2.5x on a 144Hz screen.
function runFor(ms, refreshHz) {
  const step = 1000 / refreshHz;
  const before = presented;
  for (let t = 0; t < ms; t += step) {
    fakeClock += step;
    if (pendingFrame) { const cb = pendingFrame; pendingFrame = null; cb(); }
  }
  return presented - before;
}
// The debugger's step command pauses the machine, so resume before timing it.
N.command('pause', '');
N.command('crt', '0');
fakeClock = 0;
if (pendingFrame) { const cb = pendingFrame; pendingFrame = null; cb(); }  // prime the clock
const at60 = runFor(2000, 60);
const at144 = runFor(2000, 144);
// Two seconds of wall time is ~120 NES frames whatever the display does.
check('60Hz display runs at console speed', at60 >= 100 && at60 <= 140, at60 + ' frames in 2s');
check('144Hz display does not run fast', at144 >= 100 && at144 <= 140,
      at144 + ' frames in 2s (a display-rate loop would give ~288)');

// URL loading: regression guard for the shadowed ROM handler.
elements.status.textContent = '';
N.loadUrl('nestest.nes', 'fetched.nes');
setTimeout(() => {
  check('fetched ROM loaded', /mapper 0/.test(elements.status.textContent),
        elements.status.textContent || '(no status)');
  console.log(failures === 0 ? '\nall checks passed' : '\n' + failures + ' check(s) failed');
  process.exit(failures === 0 ? 0 : 1);
}, 50);
