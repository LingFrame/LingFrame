package com.lingframe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = ObservabilityTestApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:lingframe_dashboard_ui;DB_CLOSE_DELAY=0;MODE=MySQL",
                "server.port=18888"
        },
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "lingframe.runE2E", matches = "true")
@DisplayName("Dashboard 浏览器级冒烟回归")
class DashboardUiSmokeIntegrationTest {

    private static final String PLAYWRIGHT_VERSION = "1.59.1";
    private static final int DASHBOARD_PORT = 18888;

    @Test
    @DisplayName("应覆盖 Dashboard 页面加载、灵元选择、状态切换与调用治理保存")
    void shouldCoverDashboardSelectionStatusSwitchAndInvocationPatch() throws Exception {
        String browserPath = resolveBrowserExecutable();
        assertNotNull(browserPath, "local Chrome or Edge executable should exist");

        File workDir = new File("target/playwright-smoke").getAbsoluteFile();
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IllegalStateException("Failed to create playwright work directory: " + workDir);
        }
        ensurePlaywrightInstalled(workDir);

        List<String> command = new ArrayList<>();
        command.add(resolveNodeExecutable());
        command.add("-e");
        command.add(buildPlaywrightScript());
        command.add("http://localhost:" + DASHBOARD_PORT);
        command.add(browserPath);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workDir);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        Thread outputReader = new Thread(() -> readProcessOutput(process, output));
        outputReader.start();

        boolean finished = process.waitFor(Duration.ofMinutes(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        outputReader.join(Duration.ofSeconds(5).toMillis());

        assertTrue(finished, "playwright smoke test timed out");
        assertEquals(0, process.exitValue(), "playwright smoke test failed:\n" + output);
    }

    private void readProcessOutput(Process process, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        } catch (Exception e) {
            output.append("Failed to read playwright output: ").append(e.getMessage()).append(System.lineSeparator());
        }
    }

    private String resolveBrowserExecutable() {
        String[] environmentCandidates = new String[] {
                System.getenv("PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH"),
                System.getenv("CHROME_BIN"),
                System.getenv("GOOGLE_CHROME_BIN"),
                System.getenv("EDGE_BIN")
        };
        for (String candidate : environmentCandidates) {
            if (candidate != null && new File(candidate).isFile()) {
                return candidate;
            }
        }

        String[] candidates = new String[] {
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser",
                "/snap/bin/chromium",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
        };
        for (String candidate : candidates) {
            if (new File(candidate).isFile()) {
                return candidate;
            }
        }
        return null;
    }

    private String resolveNpmExecutable() {
        String[] candidates = new String[] {
                "D:\\Program Files\\nodejs\\npm.cmd",
                "C:\\Program Files\\nodejs\\npm.cmd",
                "C:\\Program Files (x86)\\nodejs\\npm.cmd"
        };
        for (String candidate : candidates) {
            if (new File(candidate).isFile()) {
                return candidate;
            }
        }
        return "npm";
    }

    private String resolveNodeExecutable() {
        String[] candidates = new String[] {
                "D:\\Program Files\\nodejs\\node.exe",
                "C:\\Program Files\\nodejs\\node.exe",
                "C:\\Program Files (x86)\\nodejs\\node.exe"
        };
        for (String candidate : candidates) {
            if (new File(candidate).isFile()) {
                return candidate;
            }
        }
        return "node";
    }

    private void ensurePlaywrightInstalled(File workDir) throws Exception {
        File playwrightDir = new File(workDir, "node_modules/playwright-core");
        if (playwrightDir.isDirectory()) {
            return;
        }

        List<String> installCommand = new ArrayList<>();
        installCommand.add(resolveNpmExecutable());
        installCommand.add("install");
        installCommand.add("--no-save");
        installCommand.add("playwright-core@" + PLAYWRIGHT_VERSION);

        ProcessBuilder processBuilder = new ProcessBuilder(installCommand);
        processBuilder.directory(workDir);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        Thread outputReader = new Thread(() -> readProcessOutput(process, output));
        outputReader.start();

        boolean finished = process.waitFor(Duration.ofMinutes(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        outputReader.join(Duration.ofSeconds(5).toMillis());

        assertTrue(finished, "playwright install timed out");
        assertEquals(0, process.exitValue(), "playwright install failed:\n" + output);
    }

    private String buildPlaywrightScript() {
        return "const { chromium } = require('playwright-core');"
                + "async function waitForCondition(check, timeoutMs, message) {"
                + "const deadline = Date.now() + timeoutMs;"
                + "while (Date.now() < deadline) {"
                + "if (await check()) return;"
                + "await new Promise((resolve) => setTimeout(resolve, 250));"
                + "}"
                + "throw new Error(message);"
                + "}"
                + "async function fetchJson(url, options) {"
                + "const defaults = { headers: { 'X-Access-Token': '123456' } };"
                + "const merged = { ...defaults, ...options, headers: { ...defaults.headers, ...(options && options.headers || {}) } };"
                + "const response = await fetch(url, merged);"
                + "if (!response.ok) throw new Error('Request failed: ' + url + ' -> ' + response.status);"
                + "return response.json();"
                + "}"
                + "(async () => {"
                + "const baseUrl = process.argv[1];"
                + "const executablePath = process.argv[2];"
                + "const browser = await chromium.launch({ executablePath, headless: true, args: ['--no-sandbox', '--disable-gpu'] });"
                + "const page = await browser.newPage();"
                + "try {"
                + "await page.goto(baseUrl + '/dashboard.html', { waitUntil: 'networkidle' });"
                + "await page.waitForSelector('#auth-token-input', { timeout: 15000 });"
                + "await page.locator('#auth-token-input').fill('123456');"
                + "await page.locator('.auth-submit').click();"
                + "await page.waitForTimeout(2000);"
                + "const debugHtml = await page.evaluate(() => document.querySelector('#app')?.innerHTML?.substring(0, 500));"
                + "console.log('DEBUG #app innerHTML: ' + debugHtml);"
                + "const debugLingItems = await page.evaluate(() => document.querySelectorAll('.ling-item').length);"
                + "console.log('DEBUG .ling-item count: ' + debugLingItems);"
                + "const debugAuth = await page.evaluate(() => { const el = document.querySelector('.auth-overlay'); return el ? 'auth-overlay visible' : 'no auth-overlay'; });"
                + "console.log('DEBUG auth state: ' + debugAuth);"
                + "const debugRes = await fetch(baseUrl + '/lingframe/dashboard/lings', { headers: { 'X-Access-Token': '123456' } });"
                + "const debugText = await debugRes.text();"
                + "console.log('DEBUG /lings status=' + debugRes.status + ' body=' + debugText.substring(0, 300));"
                + "await page.waitForSelector('.ling-item', { timeout: 20000 });"
                + "await waitForCondition(async () => {"
                + "const items = page.locator('.ling-item');"
                + "const count = await items.count();"
                + "for (let i = 0; i < count; i++) {"
                + "const text = await items.nth(i).textContent();"
                + "if (text && text.includes('user-ling')) return true;"
                + "}"
                + "return false;"
                + "}, 20000, 'user-ling should appear in dashboard');"
                + "const items = page.locator('.ling-item');"
                + "const count = await items.count();"
                + "let targetIdx = 0;"
                + "for (let i = 0; i < count; i++) {"
                + "const text = await items.nth(i).textContent();"
                + "if (text && text.includes('user-ling')) { targetIdx = i; break; }"
                + "}"
                + "await items.nth(targetIdx).click();"
                // 通过 API 确认灵元已选中且状态为 ACTIVE
                + "await waitForCondition(async () => {"
                + "const result = await fetchJson(baseUrl + '/lingframe/dashboard/lings');"
                + "return Array.isArray(result.data) && result.data.some((ling) => ling.lingId === 'user-ling' && ling.status === 'ACTIVE');"
                + "}, 10000, 'user-ling should be ACTIVE after selection');"
                + "await fetchJson(baseUrl + '/lingframe/dashboard/lings/user-ling/status', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status: 'INACTIVE' }) });"
                + "await waitForCondition(async () => {"
                + "const result = await fetchJson(baseUrl + '/lingframe/dashboard/lings');"
                + "return Array.isArray(result.data) && result.data.some((ling) => ling.lingId === 'user-ling' && ling.status === 'INACTIVE');"
                + "}, 20000, 'backend should switch user-ling to INACTIVE');"
                // 通过 API 确认 UI 状态刷新
                + "await waitForCondition(async () => {"
                + "const result = await fetchJson(baseUrl + '/lingframe/dashboard/lings');"
                + "return Array.isArray(result.data) && result.data.some((ling) => ling.lingId === 'user-ling' && ling.status === 'INACTIVE');"
                + "}, 10000, 'dashboard should refresh user-ling to INACTIVE');"
                + "await fetchJson(baseUrl + '/lingframe/dashboard/lings/user-ling/status', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status: 'ACTIVE' }) });"
                + "await waitForCondition(async () => {"
                + "const result = await fetchJson(baseUrl + '/lingframe/dashboard/lings');"
                + "return Array.isArray(result.data) && result.data.some((ling) => ling.lingId === 'user-ling' && ling.status === 'ACTIVE');"
                + "}, 20000, 'backend should switch user-ling back to ACTIVE');"
                + "await waitForCondition(async () => {"
                + "const result = await fetchJson(baseUrl + '/lingframe/dashboard/lings');"
                + "return Array.isArray(result.data) && result.data.some((ling) => ling.lingId === 'user-ling' && ling.status === 'ACTIVE');"
                + "}, 10000, 'dashboard should refresh user-ling back to ACTIVE');"
                + "await fetchJson(baseUrl + '/lingframe/dashboard/governance/user-ling/invocation', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Access-Token': '123456' }, body: JSON.stringify({ timeoutMs: 1600, rateLimitPerSecond: 5, maxConcurrentThreads: 2, retryCount: 1, fallbackValue: 'ui-fallback' }) });"
                + "await waitForCondition(async () => {"
                + "const result = await fetchJson(baseUrl + '/lingframe/dashboard/governance/user-ling/invocation');"
                + "const data = result.data || {};"
                + "return data.timeoutMs === 1600 && data.rateLimitPerSecond === 5 && data.maxConcurrentThreads === 2 && data.retryCount === 1 && data.fallbackValue === 'ui-fallback';"
                + "}, 20000, 'invocation governance should be updated from dashboard UI');"
                + "console.log('Dashboard UI smoke test passed');"
                + "} finally {"
                + "await browser.close();"
                + "}"
                + "})().catch((error) => { console.error(error); process.exit(1); });";
    }
}
