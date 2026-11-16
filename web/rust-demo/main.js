const WASM_MODULE = '../../rustySDK/crates/wasm/pkg/rusty_qr_wasm.js';
const QR_SIZE = 256;
const RELOAD_HINT = 'The Rust module stopped working. Reload the page.';

const byId = (id) => document.getElementById(id);
const status = byId('status');
const content = byId('content');
const generate = byId('generate');
const generateError = byId('generate-error');
const qr = byId('qr');
const file = byId('file');
const decoded = byId('decoded');
const decodeError = byId('decode-error');

let wasm;
let currentUrl = null;

// A Rust panic traps the module as a WebAssembly.RuntimeError and leaves it unusable.
function describe(error) {
  return error instanceof WebAssembly.RuntimeError ? RELOAD_HINT : error.message;
}

function showGenerated(bytes) {
  if (currentUrl) {
    URL.revokeObjectURL(currentUrl);
  }
  currentUrl = URL.createObjectURL(new Blob([bytes], { type: 'image/png' }));
  qr.src = currentUrl;
  qr.hidden = false;
}

function onGenerate() {
  generateError.textContent = '';
  try {
    showGenerated(wasm.generatePng(content.value, QR_SIZE));
  } catch (error) {
    qr.hidden = true;
    generateError.textContent = describe(error);
  }
}

async function onFile() {
  decoded.textContent = '';
  decodeError.textContent = '';
  const selected = file.files[0];
  if (!selected) {
    return;
  }
  try {
    const bytes = new Uint8Array(await selected.arrayBuffer());
    decoded.textContent = wasm.decodeImage(bytes);
  } catch (error) {
    decodeError.textContent = describe(error);
  }
}

try {
  // Imported dynamically so a missing pkg/ reaches this catch instead of failing the whole module.
  wasm = await import(WASM_MODULE);
  await wasm.default();
  status.textContent = `Rust core ${wasm.libraryVersion()} loaded.`;
  content.disabled = false;
  generate.disabled = false;
  file.disabled = false;
  generate.addEventListener('click', onGenerate);
  content.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      onGenerate();
    }
  });
  file.addEventListener('change', onFile);
} catch (error) {
  status.textContent = `Could not load the Rust module: ${error.message}. Run wasm-pack build first.`;
  status.className = 'error';
}
