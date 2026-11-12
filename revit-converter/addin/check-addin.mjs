import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.dirname(fileURLToPath(import.meta.url));
const read = file => readFileSync(path.join(root, file), 'utf8');
const app = read('ConverterApplication.cs');
const exporter = read('RevitExporter.cs');
const context = read('../vendor/everse/Core/GlTFExportContext.cs');
const failures = read('RevitFailureHandling.cs');
assert.match(app, /PipeOptions\.Asynchronous \| PipeOptions\.CurrentUserOnly/);
assert.match(app, /nonce, pid = Environment\.ProcessId, revitVersion = version/);
assert.match(app, /H3D_RVT_SOURCE_ROOT/);
assert.match(app, /ExternalEvent\.Create/);
assert.match(app, /catch \(RevitDocumentCloseException/);
assert.match(app, /owner\.InvalidateInstance\(\)/);
assert.match(exporter, /info\.Format != "2026"/);
assert.match(exporter, /View3D\.CreateIsometric/);
assert.match(exporter, /candidate\.Name == options\.ViewName/);
assert.doesNotMatch(exporter, /\.DisplayStyle\s*=(?!=)/);
assert.match(exporter, /materials = request\.Options\.DisplayStyle == "Realistic" \? MaterialsEnum\.textures : MaterialsEnum\.materials/);
assert.match(exporter, /document\.Close\(false\)/);
assert.match(exporter, /if \(failure != null\) ExceptionDispatchInfo\.Capture\(failure\)\.Throw\(\)/);
assert.match(exporter, /ShouldStopOnError = true/);
assert.match(exporter, /using var failures = new RevitFailureHandling\(application, log\)/);
assert.match(failures, /if \(warning\) accessor\.DeleteWarning\(failure\)/);
assert.match(failures, /FailureProcessingResult\.ProceedWithRollBack/);
assert.match(failures, /FailuresProcessing -= Process/);
assert.doesNotMatch(failures + exporter + app, /DeleteElements|ResolveFailure|OverrideResult/);
assert.match(exporter, /compression = CompressionEnum\.None/);
assert.match(exporter, /relocateTo0 = false/);
assert.match(exporter, /File\.Copy\(sourcePath, copyPath, false\)/);
assert.match(exporter, /ExternalFileReferenceType\.RevitLink/);
assert.match(exporter, /SetDesiredReferenceData\(id, reference\.GetPath\(\), reference\.PathType, false\)/);
assert.match(exporter, /TransmissionData\.WriteTransmissionData\(copyModelPath, transmission\)/);
assert.doesNotMatch(exporter, /ClearTransmittedSaveAsNewCentral|GetProperty\(|BindingFlags/);
assert.doesNotMatch(app + exporter + context, /\.Save(?:As)?\(|OpenAndActivateDocument|PostCommand|ProgressBarWindow|Analytics\.Send|location\.json|precision/);
assert.match(context, /OnLinkBegin\(LinkNode node\)\s*\{\s*return RenderNodeAction\.Skip;/);
assert.doesNotMatch(context, /Compression\.Run|SettingsConfig|ExternalApplication/);
const permittedEdits = new Set([
  'Core/GlTFExportContext.cs', 'Utils/GeometryUtils.cs', 'Windows/MainWindow/Preferences.cs',
  'Materials/AssetProperties.cs', 'Materials/MaterialTextures.cs', 'Materials/RevitMaterials.cs',
  'Materials/TextureLocation.cs', 'Utils/RevitIniReader.cs', 'Export/GltfJson.cs',
]);
for (const file of JSON.parse(read('../vendor/everse/upstream-files.json').replace(/^\uFEFF/, ''))) {
  const relative = file.path.replaceAll('\\', '/');
  const bytes = readFileSync(path.join(root, '../vendor/everse', relative));
  const currentHash = createHash('sha256').update(bytes).digest('hex').toUpperCase();
  if (!permittedEdits.has(relative)) assert.equal(currentHash, file.upstreamSha256, `Unexpected upstream algorithm change: ${relative}`);
}
console.log('Add-in boundaries and unchanged upstream algorithms checked; real Revit/web acceptance remains required.');
