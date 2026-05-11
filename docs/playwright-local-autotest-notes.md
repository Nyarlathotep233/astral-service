# Playwright local automation notes

## Scope
This document records practical notes for running Playwright-based E2E checks against local Astral 3D Editor with local Chrome (no Playwright browser download).

## Prerequisites
- Frontend running: `https://localhost:8090/#/`
- Backend running: `http://127.0.0.1:8080`
- Local Chrome installed: `C:/Program Files/Google/Chrome/Application/chrome.exe`
- Node can resolve `playwright` package (for this machine we use `NODE_PATH=C:/Users/80967/AppData/Local/Temp/node_modules`)

## Scripts
- Create project smoke test
  - `tools/playwright/create-project-local-chrome.js`
- Complex scene build/edit/save test
  - `tools/playwright/complex-scene-edit-local-chrome.js`

## How to run
```powershell
$env:NODE_PATH='C:\Users\80967\AppData\Local\Temp\node_modules'
node tools/playwright/complex-scene-edit-local-chrome.js
```

## Latest complex test result
- Result file: `test-results/playwright/complex-scene-edit-local-chrome-result.json`
- Screenshot (editor): `test-results/playwright/complex-scene-edit-local-chrome-editor.png`
- Screenshot (home): `test-results/playwright/complex-scene-edit-local-chrome-home.png`

Verified flow:
1. Create a new project from home page.
2. Open editor for the created project.
3. Build/edit scene content in editor:
   - Add box and sphere
   - Rename and reposition box
   - Clone box
   - Remove sphere
4. Save to server and confirm dialog.
5. Verify API chain:
   - `POST /api/editor3d/scenes/add` success
   - `POST /api/common/upload` success (cover and scene package)
   - `PUT /api/editor3d/scenes/update/:id` success

## Key pitfalls and fixes
- Do not depend on Playwright-managed browser download in restricted network.
- Use local Chrome via `executablePath`.
- UI in this project uses many `.n-button` nodes that are not always native `button` selectors; prefer structural selectors.
- Reliable create-project confirm selector: modal `.n-button--primary-type`.
- Reliable editor save selector: `#rightOperation .n-button.mr-2` (the save button in right header).
- Save confirmation button is not always `primary`; safest is: find `.n-dialog .n-button` and click the last action button.
- `RemoveObjectCommand` can trigger `Cannot read properties of undefined (reading 'parameters')` and then break save dialog behavior.
  - Stable workaround in automation: remove by `object.parent.remove(object)` and dispatch `Hooks.useDispatchSignal('sceneGraphChanged')`.
- Opening editor from project card can fail in some runs (popup event timing).
  - Keep fallback: open `/#/editor/:id` directly using scene id from add API response.

## AI test note
Editor AI generation is no longer served by `astral-service`. The editor now
reuses `astral-llm-service` streaming API (`/api/chat/stream` via
`/llm-api/chat/stream`). Keep this backend test focused on scene/upload/save APIs.
