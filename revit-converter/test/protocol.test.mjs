import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { consumeLines } from '../src/worker.mjs';
import { validateRequest, within } from '../src/service.mjs';

test('pipe JSON frames handle split/coalesced UTF-8 strings and reject malformed data', () => {
  const state = { text:'' }, seen=[];
  consumeLines(state, '{"type":"rea', value => seen.push(value));
  consumeLines(state, 'dy"}\n{"value":"中文"}\n', value => seen.push(value));
  assert.deepEqual(seen, [{type:'ready'},{value:'中文'}]);
  assert.throws(() => consumeLines(state, 'bad\n', () => {}));
});

test('source validation confines access to explicit RVT uploads', async t => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'h3d-rvt-boundary-'));
  t.after(() => fs.rm(dir, { recursive:true, force:true }));
  const root = path.join(dir,'sources'); await fs.mkdir(root);
  const filePath = path.join(root,'sample.rvt'); await fs.writeFile(filePath,'unit-test fixture, not an RVT');
  const request = { fileId:1, filePath, options: { useDraco:true,optimize:false,exportProperty:true,view:'Default',viewName:'',displayStyle:'Colour',coordinateReference:'Origin' } };
  assert.equal(await validateRequest(request,root), await fs.realpath(filePath));
  const outside = path.join(dir,'outside.rvt'); await fs.writeFile(outside,'unit test');
  await assert.rejects(validateRequest({...request,filePath:outside},root), /outside/);
  for (const options of [
    {coordinateReference:'ProjectBasePoint'}, {displayStyle:'ViewDefault'}, {optimize:true},
    {view:'Name',viewName:' '}, {useDraco:false},
  ]) await assert.rejects(validateRequest({...request,options:{...request.options,...options}},root));
  await assert.rejects(validateRequest({...request,fileId:'1'},root), /fileId/);
  assert.equal(within(root,path.join(dir,'sources-other','file.rvt')),false);
});

test('real source paths reject a directory junction escaping the upload root', async t => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'h3d-rvt-realpath-'));
  t.after(() => fs.rm(dir, { recursive:true, force:true }));
  const root = path.join(dir,'sources'), outside = path.join(dir,'outside');
  await fs.mkdir(root); await fs.mkdir(outside);
  await fs.writeFile(path.join(outside,'sample.rvt'),'unit-only invalid RVT');
  await fs.symlink(outside,path.join(root,'linked'),'junction');
  const request = {fileId:1,filePath:path.join(root,'linked','sample.rvt'),options:{useDraco:true,optimize:false,
    exportProperty:true,view:'Default',displayStyle:'Colour',coordinateReference:'Origin'}};
  await assert.rejects(validateRequest(request,root), /outside/);
});
