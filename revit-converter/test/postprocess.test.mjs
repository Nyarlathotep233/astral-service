import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Document } from '@gltf-transform/core';
import { createIO, processModel } from '../src/postprocess.mjs';

async function fixture(t, extras = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'h3d-everse-postprocess-'));
  t.after(() => fs.rm(dir, { recursive:true, force:true }));
  const io = await createIO(), doc = new Document(), buffer = doc.createBuffer();
  const position = doc.createAccessor().setType('VEC3').setArray(new Float32Array([0,0,0,1,0,0,0,1,0])).setBuffer(buffer);
  const primitive = doc.createPrimitive().setAttribute('POSITION', position);
  const mesh = doc.createMesh('fixture').addPrimitive(primitive);
  const node = doc.createNode('Everse element').setMesh(mesh).setTranslation([10,20,30]).setExtras(extras);
  doc.createScene().addChild(node);
  const input = path.join(dir,'raw.glb'), output = path.join(dir,'compressed.glb'), report = path.join(dir,'report.json');
  await fs.writeFile(input, await io.writeBinary(doc));
  return {dir,io,input,output,report};
}

test('standard Draco GLB decodes and preserves Everse parameter names/values and transforms', async t => {
  const parameters = {'宽度':'1200 mm','Type 名称':'fixture',Count:0,Visible:false};
  const f = await fixture(t,{parameters,elementId:'42'});
  const inputBefore = await fs.readFile(f.input);
  const result = await processModel(f);
  assert.equal(result.accepted,true);
  const bytes = await fs.readFile(f.output);
  const json = JSON.parse(bytes.subarray(20,20+bytes.readUInt32LE(12)).toString());
  assert(json.extensionsRequired.includes('KHR_draco_mesh_compression'));
  assert(json.buffers.every(buffer=>buffer.uri===undefined));
  const doc = await f.io.readBinary(bytes), node = doc.getRoot().listNodes()[0];
  assert.deepEqual(node.getTranslation(),[10,20,30]);
  assert.deepEqual(node.getExtras(),{parameters,elementId:'42',BIM:{parameters:[{
    GroupName:'参数',Parameters:Object.entries(parameters).map(([name,value])=>({name,value})),
  }]}});
  assert.equal(node.getMesh().listPrimitives()[0].getAttribute('POSITION').getCount(),3);
  assert.deepEqual(await fs.readFile(f.input),inputBefore);
  assert.equal(JSON.parse(await fs.readFile(f.report,'utf8')).accepted,true);
});

test('no parameters remain absent rather than synthesizing BIM properties', async t => {
  const f = await fixture(t,{elementId:'42'});
  await processModel(f);
  const doc = await f.io.readBinary(await fs.readFile(f.output));
  assert.deepEqual(doc.getRoot().listNodes()[0].getExtras(),{elementId:'42'});
});

test('invalid source or parameter shape never publishes a GLB', async t => {
  const f = await fixture(t,{parameters:[]});
  await assert.rejects(processModel(f),/dictionary/);
  await assert.rejects(fs.stat(f.output),/ENOENT/);
  await fs.writeFile(f.input,'not a GLB');
  await assert.rejects(processModel(f));
  await assert.rejects(fs.stat(f.output),/ENOENT/);
});

test('empty exporter output cannot be reported as completed', async t => {
  const f = await fixture(t), doc = new Document();
  doc.createScene();
  await fs.writeFile(f.input,await f.io.writeBinary(doc));
  await assert.rejects(processModel(f),/no renderable mesh/);
  await assert.rejects(fs.stat(f.output),/ENOENT/);
});
