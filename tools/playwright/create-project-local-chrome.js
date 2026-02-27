const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

async function run() {
  const chromePath =
    process.env.CHROME_PATH ||
    "C:/Program Files/Google/Chrome/Application/chrome.exe";
  const appUrl = process.env.APP_URL || "https://localhost:8090/#/";
  const outputDir =
    process.env.PW_OUTPUT_DIR ||
    path.resolve(process.cwd(), "test-results", "playwright");
  const projectName = process.env.PROJECT_NAME || `AI自动化测试_${Date.now()}`;

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
    errors: [],
  };

  const browser = await chromium.launch({
    headless: true,
    executablePath: chromePath,
    args: ["--ignore-certificate-errors", "--allow-insecure-localhost"],
  });
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();

  page.on("pageerror", (e) => result.errors.push(`pageerror: ${e.message}`));

  try {
    await page.goto(appUrl, { waitUntil: "domcontentloaded", timeout: 60000 });
    await page.waitForTimeout(2000);
    result.pageLoaded = true;

    const addRespPromise = page
      .waitForResponse(
        (r) =>
          r.url().includes("/api/editor3d/scenes/add") &&
          r.request().method() === "POST",
        { timeout: 30000 }
      )
      .catch(() => null);

    await page.locator("p:has-text('新建项目')").first().click();
    await page.waitForTimeout(800);
    result.createDialogOpened = await page
      .locator("text=项目默认设置")
      .first()
      .isVisible()
      .catch(() => false);

    await page.locator("input[placeholder='请输入场景名称']").first().fill(projectName);

    const descInput = page
      .locator("input[placeholder='请输入场景描述'], textarea[placeholder='请输入场景描述']")
      .first();
    if (await descInput.count()) {
      await descInput.fill("Playwright 自动化创建项目验证");
    }

    await page
      .locator("button:has-text('确定'), .n-button:has-text('确定')")
      .first()
      .click();

    const addResp = await addRespPromise;
    if (addResp) {
      let body = null;
      try {
        body = await addResp.json();
      } catch (e) {
        result.errors.push(`addApi json parse failed: ${e.message}`);
      }
      result.addApi = {
        status: addResp.status(),
        url: addResp.url(),
        body,
      };
    } else {
      result.errors.push("No /api/editor3d/scenes/add response captured");
    }

    await page.reload({ waitUntil: "domcontentloaded", timeout: 60000 });
    await page.waitForTimeout(2000);
    result.cardVisibleAfterReload = await page
      .getByText(projectName, { exact: false })
      .first()
      .isVisible()
      .catch(() => false);

    await page.screenshot({
      path: path.join(outputDir, "create-project-local-chrome.png"),
      fullPage: true,
    });
  } catch (e) {
    result.errors.push(`fatal: ${e.message}`);
  } finally {
    await browser.close();
  }

  const outPath = path.join(outputDir, "create-project-local-chrome-result.json");
  fs.writeFileSync(outPath, JSON.stringify(result, null, 2));
  console.log(JSON.stringify({ outPath, result }, null, 2));
}

run().catch((e) => {
  console.error(e);
  process.exit(1);
});
