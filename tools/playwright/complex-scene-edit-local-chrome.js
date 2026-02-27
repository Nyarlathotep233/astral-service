const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

async function safeJson(response) {
  try {
    return await response.json();
  } catch (error) {
    return null;
  }
}

async function run() {
  const chromePath =
    process.env.CHROME_PATH ||
    "C:/Program Files/Google/Chrome/Application/chrome.exe";
  const appUrl = process.env.APP_URL || "https://localhost:8090/#/";
  const outputDir =
    process.env.PW_OUTPUT_DIR ||
    path.resolve(process.cwd(), "test-results", "playwright");
  const projectName =
    process.env.PROJECT_NAME || `PWScene_${String(Date.now()).slice(-8)}`;

  fs.mkdirSync(outputDir, { recursive: true });

  const result = {
    runAt: new Date().toISOString(),
    chromePath,
    appUrl,
    projectName,
    pageLoaded: false,
    createDialogOpened: false,
    addApi: null,
    cardVisibleAfterReload: false,
    editorOpened: false,
    sceneEdit: null,
    sceneEditSucceeded: false,
    editedNameVisible: false,
    cloneNameVisible: false,
    saveButtonCount: 0,
    saveButtonText: null,
    saveClicked: false,
    saveDialogVisible: false,
    saveConfirmed: false,
    saveApiSuccess: false,
    updateApi: null,
    uploadApiCalls: [],
    uploadSuccessCount: 0,
    errors: [],
  };

  const browser = await chromium.launch({
    headless: true,
    executablePath: chromePath,
    args: ["--ignore-certificate-errors", "--allow-insecure-localhost"],
  });
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();

  page.on("pageerror", (e) => result.errors.push(`home pageerror: ${e.message}`));

  try {
    await page.goto(appUrl, { waitUntil: "domcontentloaded", timeout: 60000 });
    await page.waitForTimeout(2000);
    result.pageLoaded = true;

    const addRespPromise = page
      .waitForResponse(
        (r) =>
          r.url().includes("/api/editor3d/scenes/add") &&
          r.request().method() === "POST",
        { timeout: 40000 }
      )
      .catch(() => null);

    await page.locator(".new-container").first().click({ timeout: 20000 });
    const modal = page
      .locator(".n-modal")
      .filter({ has: page.locator("input") })
      .first();
    await modal.waitFor({ state: "visible", timeout: 20000 });
    result.createDialogOpened = true;

    const inputs = modal.locator("input");
    const inputCount = await inputs.count();
    if (inputCount < 1) {
      throw new Error("Create modal input not found");
    }
    await inputs.nth(0).fill(projectName);

    if (inputCount > 1) {
      await inputs.nth(1).fill("Playwright complex scene editing");
    } else {
      const desc = modal.locator("textarea");
      if (await desc.count()) {
        await desc.first().fill("Playwright complex scene editing");
      }
    }

    await modal.locator(".n-button--primary-type").first().click();

    const addResp = await addRespPromise;
    if (addResp) {
      const body = await safeJson(addResp);
      result.addApi = {
        status: addResp.status(),
        url: addResp.url(),
        method: addResp.request().method(),
        body,
      };
    } else {
      result.errors.push("No /api/editor3d/scenes/add response captured");
    }

    await page.reload({ waitUntil: "domcontentloaded", timeout: 60000 });
    await page.waitForTimeout(2500);

    const sceneIdFromAdd = result.addApi?.body?.result?.id || null;
    let editorPage = null;

    const projectCard = page.locator(".n-card", { hasText: projectName }).first();
    const hasProjectCard = await projectCard.isVisible().catch(() => false);
    result.cardVisibleAfterReload = hasProjectCard;

    if (hasProjectCard) {
      const editorPopupPromise = context
        .waitForEvent("page", { timeout: 30000 })
        .catch(() => null);
      await projectCard
        .locator(".n-image, .n-image img, img")
        .first()
        .click({ timeout: 20000 });
      editorPage = await editorPopupPromise;
    }

    if (!editorPage && sceneIdFromAdd) {
      const origin = new URL(appUrl).origin;
      editorPage = await context.newPage();
      await editorPage.goto(`${origin}/#/editor/${sceneIdFromAdd}`, {
        waitUntil: "domcontentloaded",
        timeout: 60000,
      });
      result.errors.push(
        "Project card was not clickable, used direct /#/editor/:id fallback"
      );
    }

    if (!editorPage) {
      throw new Error("Unable to open editor page");
    }
    result.editorOpened = true;

    editorPage.on("pageerror", (e) =>
      result.errors.push(`editor pageerror: ${e.message}`)
    );
    editorPage.on("response", (r) => {
      if (r.url().includes("/api/common/upload")) {
        result.uploadApiCalls.push({
          url: r.url(),
          status: r.status(),
          method: r.request().method(),
        });
      }
    });

    await editorPage.waitForLoadState("domcontentloaded", { timeout: 60000 });
    await editorPage.waitForFunction(() => !!window.viewer && !!window.viewer.scene, {
      timeout: 120000,
    });
    await editorPage.waitForTimeout(2000);

    const sceneEdit = await editorPage.evaluate(async () => {
      const sdk = await import(
        "/@fs/C:/project/3D/Astral3D/Astral3D/packages/sdk/dist/astral3d.es.js"
      );
      const THREE = await import("/node_modules/.vite/deps/three.js");

      const {
        App,
        Box,
        Sphere,
        Hooks,
        AddObjectCommand,
      } = sdk;

      const scene = App.scene;
      const beforeCount = scene.children.length;
      const now = Date.now();

      const createBox = () => {
        try {
          return new Box();
        } catch (e) {
          return new THREE.Mesh(
            new THREE.BoxGeometry(1, 1, 1),
            new THREE.MeshStandardMaterial({ color: 0x66ccff })
          );
        }
      };

      const createSphere = () => {
        try {
          return new Sphere();
        } catch (e) {
          return new THREE.Mesh(
            new THREE.SphereGeometry(0.6, 24, 16),
            new THREE.MeshStandardMaterial({ color: 0xffaa55 })
          );
        }
      };

      const box = createBox();
      box.name = `PW_Box_${now}`;
      box.position.set(0.8, 0.5, 0.0);
      App.execute(new AddObjectCommand(box));

      const sphere = createSphere();
      sphere.name = `PW_Sphere_${now}`;
      sphere.position.set(-0.9, 0.6, -0.4);
      App.execute(new AddObjectCommand(sphere));

      const editedName = `${box.name}_Edited`;
      box.name = editedName;

      const targetPos = new THREE.Vector3(1.7, 1.1, -0.7);
      box.position.copy(targetPos);

      const clone = box.clone();
      clone.name = `${editedName}_Clone`;
      clone.position.copy(targetPos.clone().add(new THREE.Vector3(0.7, 0, 0.3)));
      App.execute(new AddObjectCommand(clone));

      if (sphere.parent) sphere.parent.remove(sphere);

      Hooks.useDispatchSignal("sceneGraphChanged");

      return {
        beforeCount,
        afterCount: scene.children.length,
        editedName,
        cloneName: clone.name,
        removedName: sphere.name,
        sceneInfo: App.project.getKey("sceneInfo"),
        sceneChildNames: scene.children.map((obj) => obj.name).slice(0, 40),
      };
    });

    result.sceneEdit = sceneEdit;
    result.sceneEditSucceeded =
      !!sceneEdit && sceneEdit.afterCount >= sceneEdit.beforeCount + 1;

    result.editedNameVisible = await editorPage
      .getByText(sceneEdit.editedName, { exact: false })
      .first()
      .isVisible()
      .catch(() => false);
    result.cloneNameVisible = await editorPage
      .getByText(sceneEdit.cloneName, { exact: false })
      .first()
      .isVisible()
      .catch(() => false);

    const updateRespPromise = editorPage
      .waitForResponse(
        (r) =>
          r.url().includes("/api/editor3d/scenes/update/") &&
          ["POST", "PUT", "PATCH"].includes(r.request().method()),
        { timeout: 180000 }
      )
      .catch(() => null);

    const saveButtons = editorPage.locator("#rightOperation .n-button.mr-2");
    result.saveButtonCount = await saveButtons.count();
    result.saveButtonText = await saveButtons.first().innerText().catch(() => null);

    await saveButtons.first().click({ timeout: 20000 });
    result.saveClicked = true;

    let dialogVisible = await editorPage
      .locator(".n-dialog")
      .first()
      .isVisible({ timeout: 5000 })
      .catch(() => false);

    if (!dialogVisible) {
      await editorPage.evaluate(() => {
        const btn = document.querySelector("#rightOperation .n-button.mr-2");
        if (!btn) return;
        btn.dispatchEvent(
          new MouseEvent("click", {
            bubbles: true,
            cancelable: true,
            view: window,
          })
        );
      });
    }

    await editorPage
      .locator(".n-dialog")
      .first()
      .waitFor({ state: "visible", timeout: 30000 });
    result.saveDialogVisible = true;

    const dialogButtons = editorPage.locator(".n-dialog .n-button");
    const dialogButtonCount = await dialogButtons.count();
    if (dialogButtonCount < 1) {
      throw new Error("Save confirm dialog button not found");
    }
    await dialogButtons.nth(dialogButtonCount - 1).click({ timeout: 20000 });
    result.saveConfirmed = true;

    const updateResp = await updateRespPromise;
    if (updateResp) {
      const body = await safeJson(updateResp);
      result.updateApi = {
        status: updateResp.status(),
        url: updateResp.url(),
        method: updateResp.request().method(),
        body,
      };
      result.saveApiSuccess =
        updateResp.status() === 200 &&
        !!(
          body?.success === true ||
          body?.code === 200 ||
          body?.status === 200 ||
          body?.result
        );
    } else {
      result.errors.push(
        "No /api/editor3d/scenes/update response captured after save"
      );
    }

    await editorPage.waitForTimeout(8000);
    result.uploadSuccessCount = result.uploadApiCalls.filter(
      (x) => x.status === 200
    ).length;

    await editorPage.screenshot({
      path: path.join(outputDir, "complex-scene-edit-local-chrome-editor.png"),
      fullPage: true,
    });
    await page.screenshot({
      path: path.join(outputDir, "complex-scene-edit-local-chrome-home.png"),
      fullPage: true,
    });
  } catch (error) {
    result.errors.push(`fatal: ${error.message}`);
  } finally {
    await browser.close();
  }

  const outPath = path.join(outputDir, "complex-scene-edit-local-chrome-result.json");
  fs.writeFileSync(outPath, JSON.stringify(result, null, 2));
  console.log(JSON.stringify({ outPath, result }, null, 2));
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
