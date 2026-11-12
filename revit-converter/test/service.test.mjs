import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter, once } from 'node:events';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { WebSocket } from 'ws';
import { Document } from '@gltf-transform/core';
import { createIO } from '../src/postprocess.mjs';
import { startService } from '../src/service.mjs';

class TestWorker extends EventEmitter {
  calls = []; recoveries = 0; failRecovery = false;
  async start() {}
  async stop() {}
  async recover() { this.recoveries++; if (this.failRecovery) throw new Error('test recovery failure'); }
  async convert(request, { signal }) {
    this.calls.push(request.fileId);
    if (request.fileId === 1) {
      setImmediate(() => this.emit('lost', new Error('test owned worker crashed')));
      await new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), {once:true}));
    }
    const glbPath = path.join(request.workDir,'model.glb');
    await fs.writeFile(glbPath, 'test-only extractor fixture');
    return { glbPath };
  }
}

async function testPostprocessor(configPath) {
  const c = JSON.parse(await fs.readFile(configPath,'utf8'));
  await fs.writeFile(c.output,'test-only postprocessor fixture');
  await fs.writeFile(c.report,JSON.stringify({accepted:true}));
}

test('crashed task fails once, recovers only owned worker, and is not replayed', async t => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(),'h3d-rvt-service-unit-'));
  const worker = new TestWorker();
  const service = await startService({sourceRoot:path.join(dir,'sources'),workRoot:path.join(dir,'work'),port:0,worker,postprocess:testPostprocessor});
  const socket = new WebSocket(`ws://127.0.0.1:${service.server.address().port}`);
  t.after(async () => { socket.terminate(); await service.close(); await fs.rm(dir,{recursive:true,force:true}); });
  await once(socket,'open');
  const filePath = path.join(dir,'sources','sample.rvt'); await fs.writeFile(filePath,'test-only invalid RVT');
  const options = {useDraco:true,optimize:false,exportProperty:true,view:'Default',viewName:'',displayStyle:'Colour',coordinateReference:'Origin'};
  async function submit(fileId) {
    const result = new Promise(resolve => {
      const handler = data => { const message = JSON.parse(data.toString()); if (message.fileId===fileId && message.type!=='progress') {socket.off('message',handler); resolve(message);} };
      socket.on('message',handler);
    });
    socket.send(JSON.stringify({fileId,filePath,options})); return result;
  }
  const first = await submit(1);
  assert.equal(first.type,'failed'); assert.match(first.message,/crashed/);
  const second = await submit(2);
  assert.equal(second.type,'completed'); assert.equal(worker.recoveries,1); assert.deepEqual(worker.calls,[1,2]);
  assert.equal(await fs.readFile(filePath,'utf8'),'test-only invalid RVT');
});

test('recovery failure stays unavailable instead of restarting repeatedly or reporting success', async t => {
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'h3d-rvt-recovery-unit-'));
  const worker=new TestWorker(); worker.failRecovery=true;
  const service=await startService({sourceRoot:path.join(dir,'sources'),workRoot:path.join(dir,'work'),port:0,worker,postprocess:testPostprocessor});
  const socket=new WebSocket(`ws://127.0.0.1:${service.server.address().port}`);
  t.after(async()=>{socket.terminate();await service.close();await fs.rm(dir,{recursive:true,force:true});});
  await once(socket,'open');
  const filePath=path.join(dir,'sources','sample.rvt'); await fs.writeFile(filePath,'unit test');
  const options={useDraco:true,optimize:false,exportProperty:true,view:'Default',displayStyle:'Colour',coordinateReference:'Origin'};
  const replies=[];
  const failed = new Promise(resolve=>socket.on('message',b=>{const m=JSON.parse(b.toString()); if(m.type==='failed'){replies.push(m);if(replies.length===1)socket.send(JSON.stringify({fileId:2,filePath,options}));else resolve();}}));
  socket.send(JSON.stringify({fileId:1,filePath,options})); await failed;
  assert.equal(worker.recoveries,1); assert.deepEqual(worker.calls,[1]); assert.match(replies[1].message,/unavailable/);
});

test('abort while recording verified output cannot publish a late success', async t => {
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'h3d-rvt-terminal-unit-'));
  const worker=new TestWorker();
  const appendFile=fs.appendFile;
  fs.appendFile=async (file,data,...args)=>{
    await appendFile(file,data,...args);
    if (String(file).startsWith(dir) && String(data).includes('"type":"output-verified"'))
      worker.emit('lost',new Error('test crash at final publication boundary'));
  };
  const service=await startService({sourceRoot:path.join(dir,'sources'),workRoot:path.join(dir,'work'),port:0,worker,postprocess:testPostprocessor});
  const socket=new WebSocket(`ws://127.0.0.1:${service.server.address().port}`);
  t.after(async()=>{fs.appendFile=appendFile;socket.terminate();await service.close();await fs.rm(dir,{recursive:true,force:true});});
  await once(socket,'open');
  const filePath=path.join(dir,'sources','sample.rvt');await fs.writeFile(filePath,'unit test');
  const result=new Promise(resolve=>socket.on('message',b=>{const m=JSON.parse(b);if(m.type!=='progress')resolve(m);}));
  socket.send(JSON.stringify({fileId:2,filePath,options:{useDraco:true,optimize:false,exportProperty:true,view:'Default',displayStyle:'Colour',coordinateReference:'Origin'}}));
  const terminal=await result;
  assert.equal(terminal.type,'failed');assert.match(terminal.message,/publication boundary/);
  assert.deepEqual(await fs.readdir(path.dirname(filePath)),['sample.rvt']);
  assert.deepEqual(worker.calls,[2]);
});

test('execution timeout fails once and recovers without retrying the timed-out model', async t => {
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'h3d-rvt-timeout-unit-'));
  const worker=new TestWorker();
  worker.convert=async (request,{signal})=>{
    worker.calls.push(request.fileId);
    await new Promise((resolve,reject)=>signal.addEventListener('abort',()=>reject(signal.reason),{once:true}));
  };
  const service=await startService({sourceRoot:path.join(dir,'sources'),workRoot:path.join(dir,'work'),port:0,taskMs:20,worker,postprocess:testPostprocessor});
  const socket=new WebSocket(`ws://127.0.0.1:${service.server.address().port}`);
  t.after(async()=>{socket.terminate();await service.close();await fs.rm(dir,{recursive:true,force:true});});
  await once(socket,'open');
  const filePath=path.join(dir,'sources','sample.rvt');await fs.writeFile(filePath,'unit test');
  const result=new Promise(resolve=>socket.on('message',b=>{const m=JSON.parse(b);if(m.type!=='progress')resolve(m);}));
  socket.send(JSON.stringify({fileId:2,filePath,options:{useDraco:true,optimize:false,exportProperty:true,view:'Default',displayStyle:'Colour',coordinateReference:'Origin'}}));
  assert.match((await result).message,/execution limit/);
  assert.equal(worker.recoveries,1);assert.deepEqual(worker.calls,[2]);
});

test('real postprocessor publishes only decoded GLB and invalid raw output fails without source changes', async t => {
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'h3d-everse-pipeline-unit-'));
  const io=await createIO(), doc=new Document(), buffer=doc.createBuffer();
  const position=doc.createAccessor().setType('VEC3').setArray(new Float32Array([0,0,0,1,0,0,0,1,0])).setBuffer(buffer);
  doc.createScene().addChild(doc.createNode().setExtras({parameters:{Name:'unit fixture'}})
    .setMesh(doc.createMesh().addPrimitive(doc.createPrimitive().setAttribute('POSITION',position))));
  const raw=await io.writeBinary(doc), worker=new TestWorker();
  worker.convert=async request=>{
    const glbPath=path.join(request.workDir,'model.glb');
    await fs.writeFile(glbPath,request.fileId===2?raw:Buffer.from('invalid raw GLB'));
    return {glbPath};
  };
  const service=await startService({sourceRoot:path.join(dir,'sources'),workRoot:path.join(dir,'work'),port:0,worker});
  const socket=new WebSocket(`ws://127.0.0.1:${service.server.address().port}`);
  t.after(async()=>{socket.terminate();await service.close();await fs.rm(dir,{recursive:true,force:true});});
  await once(socket,'open');
  const pong=once(socket,'message');socket.send('ping');assert.equal(String((await pong)[0]),'pong');
  const filePath=path.join(dir,'sources','sample.rvt');await fs.writeFile(filePath,'unit source; never passed to Revit');
  const options={useDraco:true,optimize:false,exportProperty:true,view:'Default',displayStyle:'Realistic',coordinateReference:'Origin'};
  async function submit(fileId){
    const terminal=new Promise(resolve=>{
      const listener=bytes=>{const reply=JSON.parse(bytes);if(reply.fileId===fileId&&reply.type!=='progress'){
        socket.off('message',listener);resolve(reply);
      }};socket.on('message',listener);
    });
    socket.send(JSON.stringify({fileId,filePath,options}));return terminal;
  }
  const completed=await submit(2);
  assert.equal(completed.type,'completed');assert.equal(completed.filePath,filePath);assert.equal(completed.fileId,2);
  assert.equal(path.basename(completed.gltfPath),completed.gltfPath);
  const product=await io.readBinary(await fs.readFile(path.join(path.dirname(filePath),completed.gltfPath)));
  assert.equal(product.getRoot().listNodes()[0].getExtras().BIM.parameters[0].Parameters[0].value,'unit fixture');
  const failed=await submit(3);assert.equal(failed.type,'failed');assert.match(failed.message,/postprocessing failed/);
  assert.equal(await fs.readFile(filePath,'utf8'),'unit source; never passed to Revit');
  assert.equal((await fs.readdir(path.dirname(filePath))).filter(name=>name.endsWith('.glb')).length,1);
});
