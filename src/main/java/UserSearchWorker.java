import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.openqa.selenium.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** One explicit handle, one account-only result, one independently verified profile. */
public final class UserSearchWorker {
    private static final List<String> ROUTE = List.of("SEARCH", "SEARCH_FORM", "QUERY_ENTERED",
            "SEARCH_RESULTS", "ACCOUNT_RESULTS", "MATCHED_PROFILE");

    public static void run(String app) throws Exception {
        // Validate user intent before launching services, touching a device, or preparing navigation.
        String handle = UserSearchPolicy.handle(app, System.getenv("LAB_SEARCH_HANDLE"));
        String device = System.getenv().getOrDefault("LAB_DEVICE", app.equals("tiktok") ? "emulator-5556" : "emulator-5554");
        Path dir = Path.of("lab-runs", app + "-user-search-" + Instant.now().toString().replace(':', '-')).toAbsolutePath();
        Files.createDirectories(dir);
        var report = new NavigationRunReport(app, device, "user-search", dir);
        report.routeNames(ROUTE);
        var log = new ArrayList<String>();
        var context = new LinkedHashMap<String, Object>();
        context.put("targetHandle", handle); // Local report only. Never included in model prompts or stdout.
        context.put("matching", "exact username, case-insensitive, optional leading @; no fuzzy/display-name matching");
        AndroidDriver driver = null;
        Runner runner = null;
        boolean passed = false;
        String reason = "NOT_STARTED";
        try {
            Path contractPath = Path.of(System.getenv().getOrDefault("LAB_SEARCH_UI_CONTRACT",
                    "startup-diagnosis/user-search/" + app + "-ui.local.json"));
            var contract = UserSearchPolicy.load(app, contractPath);
            context.put("uiContract", contractPath.toAbsolutePath().toString());
            // Reuse the tested LLM navigation goal; its PASS is preparation, never user-search success.
            Path preparation = SocialAppLabWorker.runNavigation(new String[]{app, "search"});
            context.put("preparationReport", preparation.resolve("report.md").toString());
            report.action("preparation", "Verified Home -> Search; see preparationReport", "observed navigation controls", "SEARCH");
            var options = new UiAutomator2Options().setUdid(device).setDeviceName(device).setNoReset(true);
            options.setCapability("appium:autoLaunch", false);
            options.setCapability("appium:newCommandTimeout", 180);
            driver = new AndroidDriver(URI.create("http://127.0.0.1:4723").toURL(), options);
            driver.setSetting("waitForIdleTimeout", 1000);
            runner = new Runner(app, handle, contract, driver, dir, report, log);
            runner.execute();
            context.put("verifiedProfileHandle", runner.observedHandle);
            passed = true;
            reason = "VERIFIED_EXACT_ACCOUNT_PROFILE";
        } catch (Exception e) {
            reason = e instanceof IllegalStateException ? String.valueOf(e.getMessage()) : "SERVICE_OR_UI_ERROR: " + e.getClass().getSimpleName();
            int step = runner == null ? 0 : runner.step;
            report.stopContext(step, runner == null ? "validating configuration / preparing Search" : "verifying " + ROUTE.get(step));
            report.failStep(step, reason);
            log.add("STOP: " + reason);
            if (driver != null) try { snapshot(driver, dir, "failure"); } catch (Exception ignored) { log.add("Failure screenshot unavailable"); }
        } finally {
            if (driver != null) try { driver.quit(); } catch (Exception ignored) {
                log.add("Driver cleanup failed");
                if (passed) { passed = false; reason = "DRIVER_CLEANUP_FAILED"; }
            }
            report.context(context);
            log.add((passed ? "PASS" : "FAIL") + ": " + reason);
            Files.write(dir.resolve("report.txt"), log);
            report.write(passed, reason, runner == null ? 0 : runner.taps, log);
            System.out.println("User-search report: " + dir.resolve("report.md"));
            System.out.println((passed ? "PASS" : "FAIL") + ": " + reason);
        }
        if (!passed) throw new IllegalStateException("User search not verified; inspect the local report");
    }

    @FunctionalInterface private interface Operation { void run() throws Exception; }
    @FunctionalInterface private interface Check { void verify(UserSearchPolicy.Ui ui); }
    private static final class Runner {
        private final String app, handle, pkg, inputId;
        private final UserSearchPolicy.Contract contract;
        private final AndroidDriver driver;
        private final Path dir;
        private final NavigationRunReport report;
        private final List<String> log;
        private int step, taps;
        private String observedHandle;
        Runner(String app, String handle, UserSearchPolicy.Contract contract, AndroidDriver driver,
               Path dir, NavigationRunReport report, List<String> log) {
            this.app = app; this.handle = handle; this.contract = contract; this.driver = driver;
            this.dir = dir; this.report = report; this.log = log; pkg = UserSearchPolicy.pkg(app);
            inputId = pkg + (app.equals("instagram") ? ":id/action_bar_search_edit_text" : ":id/hu0");
        }
        private UserSearchPolicy.Ui observe() throws Exception {
            var ui = new UserSearchPolicy.Ui(app, driver.getPageSource());
            if (!pkg.equals(driver.getCurrentPackage())) throw new IllegalStateException("APP_LEFT_FOREGROUND");
            return ui;
        }
        private WebElement unique(By locator) {
            var matches = driver.findElements(locator).stream().filter(WebElement::isDisplayed).filter(WebElement::isEnabled).toList();
            if (matches.size() != 1) throw new IllegalStateException("AMBIGUOUS_OR_MISSING_LIVE_CONTROL");
            return matches.get(0);
        }
        private void click(By locator) {
            var element = unique(locator);
            if (!"true".equals(element.getAttribute("clickable"))) throw new IllegalStateException("CONTROL_NOT_CLICKABLE");
            element.click(); taps++;
        }
        void execute() throws Exception {
            observe();
            if (NavigationPolicy.observe(app, driver.getPageSource()).destination() != NavigationPolicy.Destination.SEARCH)
                throw new IllegalStateException("START_SEARCH_NOT_VERIFIED");
            snapshot(driver, dir, "before");
            report.verified("start", "SEARCH", Set.of("verified native Search surface"), "before.png");
            step("Open/verify observed search input", inputId, () -> {
                if (app.equals("instagram")) {
                    var input = observe().one(inputId);
                    if (!input.getAttribute("class").equals("android.widget.Button") || !input.getAttribute("content-desc").equals("Search"))
                        throw new IllegalStateException("SEARCH_ENTRY_NOT_VERIFIED");
                    click(By.id(inputId));
                }
            }, ui -> ui.input(inputId), Set.of(inputId, "enabled non-password EditText"));
            step("Enter supplied exact handle", inputId, () -> {
                observe().input(inputId);
                var input = unique(By.id(inputId));
                input.clear(); input.sendKeys(handle);
            }, ui -> ui.query(inputId, handle), Set.of(inputId, "exact query value"));
            step("Submit the supplied handle query", app.equals("tiktok") ? pkg + ":id/tv_search_textview" : "IME search", () -> {
                observe().query(inputId, handle);
                if (app.equals("tiktok")) {
                    String id = pkg + ":id/tv_search_textview";
                    var submit = observe().one(id);
                    if (!submit.getAttribute("class").equals("android.widget.Button") || !submit.getAttribute("text").equals("Search"))
                        throw new IllegalStateException("SEARCH_SUBMIT_NOT_VERIFIED");
                    click(By.id(id));
                } else driver.executeScript("mobile: performEditorAction", Map.of("action", "search"));
            }, ui -> { ui.resultQuery(contract.queryId(), handle); ui.category(contract); },
                    Set.of(contract.queryId(), contract.tabContainerId(), "account category available"));
            String categoryLocator = UserSearchPolicy.categoryXpath(contract);
            step("Select/verify account-only category: " + contract.category(), categoryLocator, () -> {
                var ui = observe(); ui.resultQuery(contract.queryId(), handle); var category = ui.category(contract);
                if (!category.getAttribute("selected").equals("true")) click(By.xpath(UserSearchPolicy.categoryXpath(contract)));
            }, ui -> ui.accounts(contract, handle), Set.of(categoryLocator, contract.resultsId(), "selected account-only category"));
            var target = observe().exactAccount(contract, handle);
            step("Open unique exact-username account result", contract.usernameId(), () -> {
                var fresh = observe().exactAccount(contract, handle);
                if (!fresh.equals(target)) throw new IllegalStateException("ACCOUNT_RESULT_CHANGED");
                click(By.xpath(fresh.xpath()));
            }, ui -> ui.profile(contract, handle), profileEvidence());
            snapshot(driver, dir, "after");
            observedHandle = observe().profile(contract, handle); // Final identity check after screenshot capture.
        }
        private Set<String> profileEvidence() {
            var markers = new HashSet<>(contract.profileMarkers());
            markers.add(contract.profileHandleId()); markers.add("exact profile username matches supplied handle");
            return markers;
        }
        private void step(String label, String locator, Operation operation, Check check, Set<String> markers) throws Exception {
            step++;
            String prefix = "step-" + step;
            snapshot(driver, dir, prefix + "-before");
            report.beginStep(step, ROUTE.get(step - 1), label, locator, ROUTE.get(step), prefix + "-before.png");
            operation.run();
            report.action("deterministic user-search step " + step, label, locator, ROUTE.get(step));
            var stable = new NavigationPolicy.Stability();
            long start = System.nanoTime();
            boolean reached = false;
            String reason = "SEARCH_SCREEN_NOT_VERIFIED";
            while (Duration.ofNanos(System.nanoTime() - start).toSeconds() < 20) {
                var ui = observe(); // Login, verification, crash and foreground failures stop immediately.
                try { check.verify(ui); reason = "VERIFIED"; }
                catch (IllegalStateException e) {
                    reason = e.getMessage();
                    if (reason.contains("AMBIGUOUS") || reason.equals("PROFILE_HANDLE_MISMATCH") || reason.equals("QUERY_CHANGED")) throw e;
                }
                long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
                log.add("Step " + step + " " + ROUTE.get(step - 1) + " -> " + ROUTE.get(step) + " +" + elapsed + "ms: " + reason);
                if (stable.accept(reason, elapsed)) { reached = true; break; }
                Thread.sleep(700);
            }
            if (!reached) throw new IllegalStateException(reason.equals("VERIFIED") ? "SEARCH_SCREEN_NOT_STABLE" : reason);
            snapshot(driver, dir, prefix + "-after");
            check.verify(observe());
            report.finishStep(step, ROUTE.get(step), prefix + "-after.png", "VERIFIED");
            report.verified("step " + step, ROUTE.get(step), markers, prefix + "-after.png");
        }
    }
    private static void snapshot(AndroidDriver driver, Path dir, String name) throws Exception {
        Files.writeString(dir.resolve(name + ".xml"), driver.getPageSource());
        Files.write(dir.resolve(name + ".png"), driver.getScreenshotAs(OutputType.BYTES));
    }
}
