import init, { generatePng, decodeImage, libraryVersion } from '../../rustySDK/crates/wasm/pkg/rusty_qr_wasm.js';

const QR_SIZE = 256;

const byId = (id) => document.getElementById(id);
const status = byId('status');
const content = byId('content');
const generate = byId('generate');
const generateError = byId('generate-error');
const qr = byId('qr');
const file = byId('file');
const decoded = byId('decoded');
const decodeError = byId('decode-error');

let currentUrl = null;

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
    showGenerated(generatePng(content.value, QR_SIZE));
  } catch (error) {
    qr.hidden = true;
    generateError.textContent = error.message;
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
    decoded.textContent = decodeImage(bytes);
  } catch (error) {
    decodeError.textContent = error.message;
  }
}

try {
  await init();
  status.textContent = `Rust core ${libraryVersion()} loaded.`;
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
