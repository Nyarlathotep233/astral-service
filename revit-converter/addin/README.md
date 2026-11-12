# Revit 2026 bridge

Build from the converter directory:

```powershell
.\scripts\build-addin.ps1
dotnet run --project .\addin\tests\ProtocolChecks.csproj -c Release
```

`-RevitInstallDir` may point to the installed Revit 2026 directory. `-OutputDirectory` selects a new immutable build directory when another build is loaded. Do not overwrite a DLL loaded by a running Revit process.

Register explicitly, before starting the supervisor:

```powershell
.\scripts\register-addin.ps1
# or .\scripts\register-addin.ps1 -AssemblyPath 'C:\...\H3D.RevitConverter.dll'
```

The script creates only `H3D.EverseConverter.addin` in the current user's Revit 2026 Addins directory and refuses to overwrite an existing manifest. It does not revive the archived converter or start Revit. Disable any verification-driver registration before supervised acceptance so only one engine runs. Keep the add-in DLL's dependency files together. Handle plugin trust manually; no permanent trust/security setting is changed.

The add-in is inert unless the Node-owned process receives `H3D_RVT_CONVERTER=1`, `H3D_RVT_PIPE`, `H3D_RVT_NONCE`, `H3D_RVT_WORK_ROOT`, and `H3D_RVT_SOURCE_ROOT`. The pipe uses `CurrentUserOnly`, a PID/nonce handshake, JSON lines and one request at a time. Node owns timeout/recovery and only its own process.

Revit API work occurs only inside ExternalEvent. The bridge opens a background document, accepts only files saved in Revit 2026, creates a temporary standard isometric view for `Default`, and selects `Name` exactly. `Colour` uses upstream material colours and `Realistic` uses upstream textures; both use metres and internal coordinates, without recentering. Source files are never saved. A successful `Close(false)` is required before `extracted`; failed close invalidates the process/pipe. Unknown dialogs are not auto-confirmed.

When the uploaded model contains Revit file links, the bridge makes `workDir/source.rvt` and uses the public `TransmissionData` API only on that task copy to request those links unloaded. The original upload remains untouched. `IsTransmitted=true` is required by Revit; workshared files open detached, and the bridge never uses `ClearTransmittedSaveAsNewCentral` or saves a new central model. CAD/material/other external references are not redefined as Revit links or silently discarded. Relative non-Revit references may be affected by moving the task copy; their source resolution and unsupported external-server references remain a demo limitation to verify, not an invented link-management feature.

`ShouldStopOnError=true` surfaces actual extraction errors. Node still must parse the emitted GLB before publishing completion. No location sidecar, bespoke precision verifier, old native default-view state machine, or fallback engine is used.

A task-scoped `FailuresProcessing` handler retains the existing safety behavior: record warnings and remove only warning messages; error/corruption failures request rollback and fail the task. It never resolves failures by deleting elements and never applies generic dialog-result overrides. The handler is detached on every exit.

See `../vendor/everse/UPSTREAM.md` for source provenance and known upstream geometry/material/texture boundaries. Local protocol checks and compilation do not replace real Revit and web acceptance.
