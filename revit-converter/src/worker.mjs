import { EventEmitter } from 'node:events';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import net from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';

export function consumeLines(state, chunk, handle) {
  state.text += chunk;
  if (state.text.length > 1024 * 1024) throw new Error('Worker message exceeds 1 MiB.');
  let index;
  while ((index = state.text.indexOf('\n')) >= 0) {
    const line = state.text.slice(0, index).trim(); state.text = state.text.slice(index + 1);
    if (line) handle(JSON.parse(line));
  }
}

export class RevitWorker extends EventEmitter {
  constructor({ executable, workRoot, sourceRoot, startupMs = 300000 }) {
    super(); Object.assign(this, { executable, workRoot, sourceRoot, startupMs });
    this.ready = false; this.child = null; this.socket = null; this.starting = null;
  }

  start() {
    if (this.ready) return Promise.resolve();
    if (this.starting) return this.starting;
    this.starting = this.startSession().finally(() => { this.starting = null; });
    return this.starting;
  }

  async startSession() {
    if (this.child && this.child.exitCode === null && this.child.signalCode === null)
      throw new Error('Previous owned Revit instance has not exited.');
    const nonce = randomUUID(), pipe = `h3d-rvt-${randomUUID()}`;
    this.session = nonce;
    const child = spawn(this.executable, [], {
      // Revit requires an interactive desktop; keep licensing/add-in prompts visible during local commissioning.
      shell: false, detached: false, windowsHide: false, stdio: 'ignore',
      env: { ...process.env, H3D_RVT_CONVERTER: '1', H3D_RVT_PIPE: pipe, H3D_RVT_NONCE: nonce,
        H3D_RVT_WORK_ROOT: this.workRoot, H3D_RVT_SOURCE_ROOT: this.sourceRoot },
    });
    this.child = child;
    let exited = false, startupError;
    const lost = error => {
      if (this.session !== nonce) return;
      this.ready = false; startupError = error; this.emit('lost', error);
    };
    child.once('error', error => { exited = true; lost(new Error(`Revit could not start: ${error.message}`)); });
    child.once('exit', (code, signal) => { exited = true; this.socket?.destroy(); lost(new Error(`Owned Revit exited (${code ?? signal}).`)); });
    const deadline = Date.now() + this.startupMs;
    while (Date.now() < deadline && !exited) {
      const socket = net.connect(`\\\\.\\pipe\\${pipe}`);
      const connected = await new Promise(resolve => {
        socket.once('connect', () => resolve(true)); socket.once('error', () => resolve(false));
      });
      if (!connected) { socket.destroy(); await delay(200); continue; }
      this.socket = socket; socket.setEncoding('utf8');
      const state = { text: '' };
      const handshake = new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Revit plugin handshake timed out.')), Math.max(1, deadline-Date.now()));
        const finish = (error) => { clearTimeout(timer); error ? reject(error) : resolve(); };
        socket.on('data', chunk => {
          try {
            consumeLines(state, chunk, message => {
              if (message.type === 'ready') {
                if (message.nonce !== nonce || message.pid !== child.pid ||
                    !(message.revitVersion === '2026' || String(message.revitVersion).startsWith('26.')))
                  throw new Error('Revit plugin ownership/version handshake rejected.');
                this.ready = true; finish();
              } else if (!this.ready) throw new Error('Worker sent a task message before ownership verification.');
              else this.emit('message', message);
            });
          } catch (error) { finish(error); socket.destroy(); lost(error); }
        });
        socket.on('error', error => { finish(error); lost(error); });
        socket.on('close', () => { const error = new Error('Revit plugin pipe disconnected.'); finish(error); lost(error); });
      });
      await handshake;
      return;
    }
    throw startupError ?? new Error('Revit plugin did not become ready within 5 minutes; check licensing and plugin loading.');
  }

  convert(request, { signal, onProgress, onLog }) {
    if (!this.ready || !this.socket) return Promise.reject(new Error('Revit is not ready.'));
    return new Promise((resolve, reject) => {
      const finish = (error, value) => {
        this.off('message', message); this.off('lost', lost); signal.removeEventListener('abort', aborted);
        error ? reject(error) : resolve(value);
      };
      const lost = error => finish(error);
      const aborted = () => finish(signal.reason ?? new Error('Conversion aborted.'));
      const message = value => {
        if (String(value.fileId) !== String(request.fileId) || value.filePath !== request.filePath) return;
        if (value.type === 'progress') onProgress(value.progress);
        else if (value.type === 'log') onLog(value);
        else if (value.type === 'extracted') finish(null, value);
        else if (value.type === 'failed') finish(new Error(value.message || 'Revit extraction failed.'));
      };
      this.on('message', message); this.on('lost', lost); signal.addEventListener('abort', aborted, { once: true });
      if (signal.aborted) return aborted();
      this.socket.write(`${JSON.stringify({ ...request, type: 'convert' })}\n`, error => { if (error) finish(error); });
    });
  }

  async stop() {
    this.ready = false;
    const child = this.child;
    this.session = null;
    this.socket?.destroy(); this.socket = null;
    if (!child || child.exitCode !== null || child.signalCode !== null) return;
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Owned Revit did not exit; no replacement instance will be spawned.')), 10000);
      child.once('exit', () => { clearTimeout(timer); resolve(); });
      // Keep the owned handle: a second kill can return false while Windows is still delivering the exit event.
      child.kill();
    });
  }

  async recover() { await this.stop(); await this.start(); }
}
