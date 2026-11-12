import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { setImmediate } from 'node:timers/promises';
import { RevitWorker } from '../src/worker.mjs';

test('owned process already terminating is awaited before recovery can start', async () => {
  const worker = new RevitWorker({ executable: 'unused', workRoot: 'unused' });
  const child = Object.assign(new EventEmitter(), { exitCode: null, signalCode: null, kill: () => false });
  worker.child = child;
  let settled = false;
  const stopping = worker.stop().finally(() => { settled = true; });
  // Observe rejection without an unhandled-rejection race on the pre-fix implementation.
  stopping.catch(() => {});
  await setImmediate();
  assert.equal(settled, false, 'A false second kill is not proof that the owned process cannot exit.');
  child.signalCode = 'SIGTERM';
  child.emit('exit', null, 'SIGTERM');
  await stopping;
});

test('owned process that never exits still fails the existing stop deadline', async () => {
  const worker = new RevitWorker({ executable: 'unused', workRoot: 'unused' });
  worker.child = Object.assign(new EventEmitter(), { exitCode: null, signalCode: null, kill: () => false });
  await assert.rejects(worker.stop(), /Owned Revit did not exit; no replacement instance will be spawned/);
});
