import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';
import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import { draco } from '@gltf-transform/functions';
import draco3d from 'draco3d';

export async function createIO() {
  return new NodeIO().registerExtensions(ALL_EXTENSIONS).registerDependencies({
    'draco3d.encoder': await draco3d.createEncoderModule(),
    'draco3d.decoder': await draco3d.createDecoderModule(),
  });
}

export async function processModel({ input, output, report }) {
  const io = await createIO();
  const doc = await io.readBinary(await fs.readFile(input));
  for (const node of doc.getRoot().listNodes()) {
    const extras = node.getExtras();
    if (extras.parameters === undefined) continue;
    assert(extras.parameters && typeof extras.parameters === 'object' && !Array.isArray(extras.parameters),
      'Everse node parameters must be a dictionary.');
    node.setExtras({ ...extras, BIM: { parameters: [{ GroupName: '参数',
      Parameters: Object.entries(extras.parameters).map(([name, value]) => ({ name, value })),
    }] } });
  }
  await doc.transform(draco());
  const bytes = await io.writeBinary(doc);
  const decoded = await io.readBinary(bytes);
  const root = decoded.getRoot();
  assert(root.listMeshes().some(mesh => mesh.listPrimitives().some(primitive =>
    (primitive.getAttribute('POSITION')?.getCount() ?? 0) > 0)), 'GLB contains no renderable mesh.');
  const result = { accepted: true, bytes: bytes.length, nodes: root.listNodes().length,
    meshes: root.listMeshes().length, images: root.listTextures().length };
  const partial = `${output}.partial`;
  await fs.writeFile(partial, bytes, { flag: 'wx' });
  await fs.rename(partial, output);
  if (report) await fs.writeFile(report, JSON.stringify(result, null, 2), { flag: 'wx' });
  return result;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    const config = JSON.parse(await fs.readFile(process.argv[2], 'utf8'));
    console.log(JSON.stringify(await processModel(config)));
  } catch (error) { console.error(error.stack ?? error.message); process.exitCode = 1; }
}
