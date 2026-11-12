# Everse source boundary

- Repository: https://github.com/EverseDevelopment/revit-glTF-exporter
- Release: `v4.5.242`
- Commit: `fb1832d0784288f13927395b1b8a20f278570a17`
- License: MIT, retained in `LICENSE`.
- `upstream-files.json` records the original SHA256 of the 33 selected source files.

Only the export core and its direct utility/data dependencies are included. Desktop entry points, windows, analytics, update/download logic and upstream compression wrappers are not compiled. Raw GLB is written by Everse; Node applies standard Draco once afterward.

## Necessary bridge edits

- `Core/GlTFExportContext.cs`: explicit document/view/preferences/progress/log inputs; no global config or WPF progress; skip links; no upstream compression call.
- `Windows/MainWindow/Preferences.cs`, material path call chain: inject texture search paths without changing the upstream lookup order or missing-texture colour behavior.
- `Materials/TextureLocation.cs`, `Utils/RevitIniReader.cs`: supply Revit version instead of obtaining an active UI document.
- `Utils/GeometryUtils.cs`: supply the chosen view instead of `doc.ActiveView`; retain the upstream no-view retry behavior.
- `Export/GltfJson.cs`: fixed upstream version identification instead of desktop settings.

Geometry, winding, normals, material algorithms, texture tint/fade/re-encoding, parameters and GLB writing remain upstream behavior. This is not a complete fidelity or measurement-precision claim.

## Demo limitations retained by explicit direction

Architectural baseline has diagnosed winding/normal disagreements, including single-sided railing triangles; no automatic face repair is applied. PNGs are re-encoded through RGB24, and equal image paths share an image even when tint/fade differs. Missing referenced textures may become material colour, unsupported appearance schemas remain within upstream handling, and RPC without proxy meshes may be omitted. No attempt is made to hide these limits with alternate data or a second exporter.

Texture resolution retains existing absolute/UNC paths, the Autodesk library and user Revit.ini paths. **This local controlled demo is not a sandbox for hostile/public RVT uploads.** Deployment needs a separately agreed process/network/filesystem isolation policy; this bridge does not silently substitute a different approved texture collection.

## Fixed runtime dependencies

The core uses `glTF.Manipulator 2025.11.27.1900`, `Newtonsoft.Json 13.0.1`, `System.Drawing.Common 8.0.6`, the installed Revit 2026 API and .NET 8 Windows Desktop runtime. NuGet/transitive dependency licenses remain applicable. No Everse native Draco/MeshOpt wrapper is used.
