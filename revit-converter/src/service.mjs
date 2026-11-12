import fs from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { WebSocketServer, WebSocket } from 'ws';
import { RevitWorker } from './worker.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
export function within(root, candidate) {
  const relative = path.relative(root, candidate);
  return relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
}

export async function validateRequest(request, sourceRoot) {
  if (!request || !Number.isSafeInteger(request.fileId) || request.fileId <= 0) throw new Error('Invalid conversion fileId.');
  if (typeof request.filePath !== 'string' || !path.isAbsolute(request.filePath)) throw new Error('An absolute uploaded RVT path is required.');
  if (path.extname(request.filePath).toLowerCase() !== '.rvt') throw new Error('This converter supports RVT only.');
  const root = await fs.realpath(sourceRoot), source = await fs.realpath(request.filePath);
  if (!within(root, source) || source === root || !(await fs.stat(source)).isFile()) throw new Error('Source is outside the uploaded RVT directory.');
  const o = request.options;
  if (!o || o.useDraco !== true || o.optimize !== false || typeof o.exportProperty !== 'boolean') throw new Error('This converter requires Draco, optimize=false, and an explicit exportProperty option.');
  if (!['Default','Name'].includes(o.view) || !['Colour','Realistic'].includes(o.displayStyle) ||
      o.coordinateReference !== 'Origin') throw new Error('Unsupported conversion option.');
  if (o.view === 'Name' && (typeof o.viewName !== 'string' || !o.viewName.trim())) throw new Error('Named view is empty.');
  return source;
}

function send(socket, message) {
  if (socket.readyState === WebSocket.OPEN) socket.send(typeof message === 'string' ? message : JSON.stringify(message));
}

async function runPostprocessor(configPath, signal, log) {
  const child = spawn(process.execPath, ['--max-old-space-size=8192', path.join(here, 'postprocess.mjs'), configPath], {
    shell: false, windowsHide: true, stdio: ['ignore','pipe','pipe'],
  });
  return new Promise((resolve, reject) => {
    let stderr = '';
    const abort = () => child.kill();
    signal.addEventListener('abort', abort, { once: true });
    child.stdout.on('data', chunk => log({ stage: 'postprocess', message: String(chunk).trim() }));
    child.stderr.on('data', chunk => { stderr = (stderr + chunk).slice(-12000); });
    child.once('error', error => { signal.removeEventListener('abort', abort); reject(error); });
    child.once('close', code => {
      signal.removeEventListener('abort', abort);
      if (signal.aborted) reject(signal.reason);
      else if (code !== 0) reject(new Error(`GLB postprocessing failed: ${stderr.trim()}`));
      else resolve();
    });
    if (signal.aborted) abort();
  });
}

export async function startService({ sourceRoot, workRoot, executable, port = 8081, taskMs = 3600000, worker, postprocess = runPostprocessor }) {
  if (!sourceRoot || !workRoot) throw new Error('H3D_RVT_SOURCE_ROOT and H3D_RVT_WORK_ROOT must be explicitly configured.');
  sourceRoot = path.resolve(sourceRoot); workRoot = path.resolve(workRoot);
  await fs.mkdir(sourceRoot, { recursive: true }); await fs.mkdir(workRoot, { recursive: true });
  sourceRoot = await fs.realpath(sourceRoot); workRoot = await fs.realpath(workRoot);
  if (within(sourceRoot, workRoot) || within(workRoot, sourceRoot)) throw new Error('Source and task directories must be separate.');
  worker ??= new RevitWorker({ executable, workRoot, sourceRoot });
  const logService = event => fs.appendFile(path.join(workRoot, 'service.jsonl'), JSON.stringify({ time: new Date().toISOString(), ...event })+'\n').catch(error => console.error('Service log failed:', error.message));
  const server = new WebSocketServer({ host: '127.0.0.1', port, maxPayload: 65536,
    verifyClient: info => !info.origin,
  });
  await new Promise((resolve, reject) => { server.once('listening', resolve); server.once('error', reject); });
  let active = null, stopped = false, available = true, recovering = false, initializing = true;
  let readiness = worker.start().then(() => logService({ type: 'ready', pid: worker.child?.pid })).catch(error => {
    available = false; logService({ type: 'unavailable', message: error.message });
    return worker.stop().catch(stopError => logService({ type: 'stop-failed', message: stopError.message }));
  }).finally(() => { initializing = false; });

  function recovery() {
    if (recovering || stopped) return;
    recovering = true;
    readiness = worker.recover().then(() => { available = true; return logService({ type: 'recovered', pid: worker.child?.pid }); })
      .catch(async error => { available = false; await logService({ type: 'recovery-failed', message: error.message });
        await worker.stop().catch(stopError => logService({ type: 'stop-failed', message: stopError.message })); })
      .finally(() => { recovering = false; });
  }
  worker.on('lost', error => {
    if (stopped || initializing || recovering || !available) return;
    if (active) { active.abort.abort(error); return; }
    logService({ type: 'idle-worker-lost', message: error.message }); recovery();
  });

  server.on('connection', socket => {
    socket.on('error', error => logService({ type: 'socket-error', message: error.message }));
    socket.on('close', () => { if (active?.socket === socket) active.abort.abort(new Error('Backend disconnected; the task will not be replayed.')); });
    socket.on('message', async bytes => {
      if (bytes.toString() === 'ping') return send(socket, 'pong');
      let request;
      try { request = JSON.parse(bytes.toString()); }
      catch { socket.close(1008, 'Invalid JSON'); return; }
      const envelope = { fileId: request?.fileId, filePath: request?.filePath };
      if (active) return send(socket, { ...envelope, type: 'failed', message: 'Converter already has an active task.' });
      const job = { socket, abort: new AbortController() }; active = job;
      let timer, log = logService, recoveryNeeded = false;
      let terminal = false;
      const finish = message => {
        if (terminal) return;
        terminal = true;
        send(socket, { ...envelope, ...message });
      };
      job.abort.signal.addEventListener('abort', () => { recoveryNeeded = true; }, { once: true });
      const lost = error => { recoveryNeeded = true; job.abort.abort(error); };
      try {
        const source = await validateRequest(request, sourceRoot);
        await readiness;
        if (!available) throw new Error('Dedicated Revit instance is unavailable; check converter task logs.');
        if (stopped || job.abort.signal.aborted) throw job.abort.signal.reason ?? new Error('Service stopping.');
        const workDir = path.join(workRoot, `${request.fileId}-${randomUUID()}`);
        await fs.mkdir(workDir);
        log = event => fs.appendFile(path.join(workDir, 'task.jsonl'), JSON.stringify({ time: new Date().toISOString(), fileId: request.fileId, ...event })+'\n').catch(error => console.error('Task log failed:', error.message));
        await log({ type: 'started', options: request.options });
        timer = setTimeout(() => { recoveryNeeded = true; job.abort.abort(new Error('Conversion exceeded the 60-minute execution limit.')); }, taskMs);
        worker.on('lost', lost);
        const progress = value => {
          if (!job.abort.signal.aborted && Number.isFinite(value)) {
            log({ type: 'progress', progress: value, elapsedMs: performance.now()-started });
            send(socket, { ...envelope, type: 'progress', progress: value });
          }
        };
        const started = performance.now();
        progress(0);
        const extracted = await worker.convert({ ...request, workDir }, { signal: job.abort.signal, onProgress: progress, onLog: log });
        if (typeof extracted.glbPath !== 'string' || path.extname(extracted.glbPath).toLowerCase() !== '.glb' ||
            !within(await fs.realpath(workDir), await fs.realpath(extracted.glbPath)) ||
            !(await fs.stat(extracted.glbPath)).isFile()) throw new Error('Worker GLB output is outside its task directory or invalid.');
        progress(80);
        const output = path.join(workDir, 'verified.glb');
        const report = path.join(workDir, 'postprocess-report.json');
        const configPath = path.join(workDir, 'postprocess.json');
        await fs.writeFile(configPath, JSON.stringify({ input: extracted.glbPath, output, report }));
        await postprocess(configPath, job.abort.signal, log);
        if (job.abort.signal.aborted) throw job.abort.signal.reason;
        const verification = JSON.parse(await fs.readFile(report, 'utf8'));
        if (verification.accepted !== true) throw new Error('GLB decode verification did not pass.');
        if (!(await fs.stat(output)).size) throw new Error('Empty GLB output.');
        const gltfPath = `${request.fileId}-${randomUUID()}.glb`;
        const destination = path.join(path.dirname(source), gltfPath);
        await fs.copyFile(output, `${destination}.partial`, fs.constants.COPYFILE_EXCL);
        await fs.rename(`${destination}.partial`, destination);
        if (job.abort.signal.aborted) { await fs.unlink(destination); throw job.abort.signal.reason; }
        await log({ type: 'output-verified', ...verification });
        if (job.abort.signal.aborted) { await fs.unlink(destination); throw job.abort.signal.reason; }
        finish({ type: 'completed', gltfPath, runSeconds: (performance.now()-started)/1000 });
      } catch (error) {
        await log({ type: 'failed', message: error.message });
        finish({ type: 'failed', message: error.message });
      } finally {
        clearTimeout(timer); worker.off('lost', lost);
        if (recoveryNeeded && !stopped) recovery();
        if (active === job) active = null;
      }
    });
  });
  await logService({ type: 'listening', port });
  return { server, worker, async close() {
    stopped = true; active?.abort.abort(new Error('Converter stopped.'));
    for (const client of server.clients) client.close();
    await worker.stop(); await new Promise(resolve => server.close(resolve));
  } };
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    const service = await startService({
      sourceRoot: process.env.H3D_RVT_SOURCE_ROOT, workRoot: process.env.H3D_RVT_WORK_ROOT,
      executable: process.env.H3D_RVT_EXECUTABLE || 'D:\\software\\AutoDesk\\RVT2026\\Revit 2026\\Revit.exe',
    });
    let closing = false;
    for (const signal of ['SIGINT','SIGTERM']) process.on(signal, async () => {
      if (closing) return; closing = true;
      try { await service.close(); process.exitCode = 0; } catch (error) { console.error(error); process.exitCode = 1; }
    });
    console.log('H3D Revit converter listening on ws://127.0.0.1:8081');
  } catch (error) { console.error(error.stack); process.exitCode = 1; }
}
