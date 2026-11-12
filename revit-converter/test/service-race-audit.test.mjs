import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { EventEmitter, once } from 'node:events';
import { WebSocket } from 'ws';
import { startService } from '../src/service.mjs';

test('worker loss after readiness but before convert still triggers one recovery', async t => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'h3d-rvt-start-race-unit-'));
  class TestWorker extends EventEmitter {
    ready = false; recoveries = 0;
    async start() { this.ready = true; }
    async stop() { this.ready = false; }
    async recover() { this.recoveries++; this.ready = true; }
    async convert() { assert.equal(this.ready, true, 'Unit worker is no longer ready.'); throw new Error('Unexpected conversion'); }
  }
  const worker = new TestWorker(), appendFile = fs.appendFile;
  fs.appendFile = async (file, data, ...args) => {
    await appendFile(file, data, ...args);
    if (String(file).startsWith(dir) && String(data).includes('"type":"started"')) {
      worker.ready = false;
      worker.emit('lost', new Error('Unit worker exited in task setup window.'));
    }
  };
  const service = await startService({ sourceRoot: path.join(dir, 'sources'), workRoot: path.join(dir, 'work'), port: 0, worker });
  const socket = new WebSocket(`ws://127.0.0.1:${service.server.address().port}`);
  t.after(async () => {
    fs.appendFile = appendFile; socket.terminate(); await service.close();
    assert(path.resolve(dir).startsWith(path.join(os.tmpdir(), 'h3d-rvt-start-race-unit-')));
    await fs.rm(dir, { recursive: true, force: true });
  });
  await once(socket, 'open');
  const filePath = path.join(dir, 'sources', 'fixture.rvt');
  await fs.writeFile(filePath, 'unit-only invalid RVT; never passed to Revit');
  const result = new Promise(resolve => socket.on('message', bytes => {
    const reply = JSON.parse(bytes.toString());
    if (reply.type !== 'progress') resolve(reply);
  }));
  socket.send(JSON.stringify({ fileId: 20, filePath, options: { useDraco: true, optimize: false, exportProperty: true,
    view: 'Default', displayStyle: 'Colour', coordinateReference: 'Origin' } }));
  assert.equal((await result).type, 'failed');
  assert.equal(worker.recoveries, 1, 'The failed task must recover its owned worker once, without replay.');
});
