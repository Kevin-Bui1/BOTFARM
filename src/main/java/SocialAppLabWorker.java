import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class SocialAppLabWorker {
    private record Observation(String xml, NavigationPolicy.Screen screen) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || !Set.of("instagram", "tiktok").contains(args[0]))
            throw new IllegalArgumentException("Usage: SocialAppLabWorker tiktok|instagram [search|profile|search-to-profile]");
        long started = System.nanoTime();
        String app = args[0];
        String goalName = args.length == 2 ? args[1] : (app.equals("tiktok") ? "profile" : "search");
        String pkg = app.equals("instagram") ? "com.instagram.android" : "com.zhiliaoapp.musically";
        String device = System.getenv().getOrDefault("LAB_DEVICE", app.equals("tiktok") ? "emulator-5556" : "emulator-5554");
        String model = System.getenv("LAB_MODEL");
        Path dir = Path.of(System.getProperty("worker.projectDir", "."), "lab-runs",
                app + "-" + Instant.now().toString().replace(':', '-')).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        var report = new NavigationRunReport(app, device, goalName, dir);
        List<String> log = new ArrayList<>();
        log.add("App: " + app + "; device: " + device + "; start: " + Instant.now());
        AndroidDriver driver = null;
        boolean passed = false;
        int taps = 0;
        String reason = "NOT_STARTED";
        String stage = "validating requested goal";
        String requested = goalName.toUpperCase(Locale.ROOT);
        NavigationRoute route = null;
        int activeStep = 0;
        NavigationPolicy.Screen lastSource = null;
        NavigationPolicy.Control lastAction = null;
        try {
            route = new NavigationRoute(app, goalName);
            report.route(route.screens());
            requested = route.screens().get(route.screens().size() - 1).name();
            log.add("Test route: " + route.description());
            stage = "verifying model";
            verifyModel(model);
            log.add("Installed local Ollama model verified: " + model);
            stage = "creating native session";
            var options = new UiAutomator2Options().setUdid(device).setDeviceName(device).setNoReset(true);
            options.setCapability("appium:autoLaunch", false);
            options.setCapability("appium:newCommandTimeout", 180);
            driver = new AndroidDriver(URI.create("http://127.0.0.1:4723").toURL(), options);
            driver.setSetting("waitForIdleTimeout", 1000);
            if (!driver.isAppInstalled(pkg)) throw new IllegalStateException("APP_NOT_INSTALLED");
            stage = "launching " + app;
            driver.activateApp(pkg);
            snapshot(driver, dir, "launch");
            Observation before = awaitRecognized(driver, app, pkg, 20);
            check(before);
            if (before.screen().destination() != NavigationPolicy.Destination.HOME) {
                stage = "preparing observed Home tab";
                var home = before.screen().controls().stream()
                        .filter(c -> c.destination() == NavigationPolicy.Destination.HOME && !c.selected()).toList();
                if (home.size() != 1) throw new IllegalStateException("START_SCREEN_NOT_VERIFIED_HOME");
                snapshot(driver, dir, "preparation-before");
                click(driver, app, pkg, home.get(0));
                report.action("preparation", home.get(0));
                log.add("Preparation: tapped observed Home/return control (not counted as a verified transition)");
                before = awaitHome(driver, app, pkg, 15);
                snapshot(driver, dir, "preparation-after");
                before = observe(driver, app, pkg);
                if (before.screen().destination() != NavigationPolicy.Destination.HOME)
                    throw new IllegalStateException("HOME_PREPARATION_FAILED");
                report.verified("preparation", before.screen(), "preparation-after.png");
            }
            if (before.screen().destination() != NavigationPolicy.Destination.HOME)
                throw new IllegalStateException("START_SCREEN_NOT_VERIFIED_HOME");
            snapshot(driver, dir, "before");
            report.verified("start", before.screen(), "before.png");
            log.add("Before: " + before.screen().summary());
            while (!route.complete()) {
                activeStep = route.completed() + 1;
                var goal = route.next();
                route.requireSource(before.screen());
                String prefix = "step-" + activeStep;
                var choices = NavigationPolicy.routeChoices(before.screen());
                log.add("Observed destination controls: " + choices);
                if (choices.isEmpty()) throw new IllegalStateException("NO_UNIQUE_DESTINATION_CONTROLS");
                stage = "asking local model for destination";
                int selected = askModel(model, app, before.screen().destination(), goal, choices, activeStep, log);
                if (selected < 0) throw new IllegalStateException("MODEL_STOP");
                var action = choices.get(selected);
                NavigationPolicy.requireGoal(goal, action);
                log.add("Step " + activeStep + ": " + route.source() + " -> " + goal + "; locator: " + action.id());
                stage = "rechecking observed control";
                before = observe(driver, app, pkg);
                check(before);
                route.requireSource(before.screen());
                if (!NavigationPolicy.routeChoices(before.screen()).contains(action))
                    throw new IllegalStateException("REPEATED_OR_CHANGED_CONTROL");
                snapshot(driver, dir, prefix + "-before");
                click(driver, app, pkg, action, route.source());
                taps++;
                report.action("LLM-selected step " + activeStep, action);
                report.beginStep(activeStep, before.screen().destination(), action, prefix + "-before.png");
                log.add("Tapped: " + (action.label().isEmpty() ? "Return to Home (unlabelled icon)" : action.label()));
                stage = "verifying step " + activeStep + " destination " + goal;
                var stable = new NavigationPolicy.Stability();
                long transitionStarted = System.nanoTime();
                String verdict = "DESTINATION_NOT_VERIFIED";
                boolean reached = false;
                while (Duration.ofNanos(System.nanoTime() - transitionStarted).toSeconds() < 20) {
                    Observation after = observe(driver, app, pkg);
                    check(after);
                    verdict = NavigationPolicy.routeTransition(before.screen(), action, after.screen());
                    long elapsed = Duration.ofNanos(System.nanoTime() - transitionStarted).toMillis();
                    log.add("Step " + activeStep + " observation +" + elapsed + "ms: " + after.screen().summary() + "; verdict=" + verdict);
                    reached = stable.accept(verdict, elapsed);
                    if (reached) break;
                    Thread.sleep(700);
                }
                snapshot(driver, dir, prefix + "-after");
                // Screenshot capture may take time: verify the requested destination again afterward.
                Observation finalObservation = observe(driver, app, pkg);
                check(finalObservation);
                String finalVerdict = NavigationPolicy.routeTransition(before.screen(), action, finalObservation.screen());
                if (!reached || !finalVerdict.equals("VERIFIED"))
                    throw new IllegalStateException(finalVerdict.equals("VERIFIED") ? "DESTINATION_NOT_STABLE" : finalVerdict);
                route.advance(before.screen(), action, finalObservation.screen());
                report.finishStep(activeStep, finalObservation.screen().destination(), prefix + "-after.png", "VERIFIED");
                report.verified("step " + activeStep, finalObservation.screen(), prefix + "-after.png");
                log.add("After: " + finalObservation.screen().summary());
                log.add("Verified requested destination using app-specific UI evidence across at least 3 observations spanning 2 seconds");
                lastSource = before.screen();
                lastAction = action;
                before = finalObservation;
            }
            snapshot(driver, dir, "after");
            var finalScreen = observe(driver, app, pkg).screen();
            String finalVerdict = NavigationPolicy.routeTransition(lastSource, lastAction, finalScreen);
            if (!finalVerdict.equals("VERIFIED")) throw new IllegalStateException("FINAL_DESTINATION_CHANGED: " + finalVerdict);
            reason = route.completed() > 1 ? "VERIFIED_ROUTE" : "VERIFIED_DESTINATION";
            passed = true;
        } catch (Exception e) {
            reason = e instanceof IllegalStateException ? String.valueOf(e.getMessage()) : "SERVICE_OR_UI_ERROR: " + e.getClass().getSimpleName();
            log.add("STOP at route step " + activeStep + " while " + stage + ": " + reason);
            report.stopContext(activeStep, stage);
            report.failStep(activeStep, reason);
            if (driver != null) {
                try { snapshot(driver, dir, "failure"); }
                catch (Exception ignored) { log.add("Failure UI evidence unavailable"); }
            }
            if (driver != null) collectAndroidEvidence(device, pkg, dir, log);
        } finally {
            if (driver != null) try { driver.quit(); }
            catch (Exception ignored) { passed = false; reason = "DRIVER_CLEANUP_FAILED"; }
            log.add((passed ? (route.completed() > 1 ? "PASS: verified route " + route.description()
                    : "PASS: verified transition HOME -> " + requested) : "FAIL: " + reason)
                    + "; destination taps: " + taps);
            log.add(String.format(Locale.ROOT, "Duration: %.3f seconds", (System.nanoTime() - started) / 1_000_000_000.0));
            Files.write(dir.resolve("report.txt"), log, StandardCharsets.UTF_8);
            report.write(passed, reason, taps, log);
            System.out.println("Report: " + dir.resolve("report.txt"));
            log.forEach(System.out::println);
        }
        if (!passed) throw new IllegalStateException("Navigation not verified; inspect " + dir.resolve("report.txt"));
    }

    private static Observation observe(AndroidDriver driver, String app, String pkg) throws Exception {
        String xml = driver.getPageSource();
        var screen = NavigationPolicy.observe(app, xml);
        if (!screen.blocker().isEmpty()) throw new IllegalStateException(screen.blocker());
        if (!pkg.equals(driver.getCurrentPackage())) throw new IllegalStateException("APP_LEFT_FOREGROUND");
        return new Observation(xml, screen);
    }

    private static void check(Observation observation) {
        if (!observation.screen().blocker().isEmpty()) throw new IllegalStateException(observation.screen().blocker());
    }

    private static Observation awaitRecognized(AndroidDriver driver, String app, String pkg, int seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        Observation observation;
        do {
            observation = observe(driver, app, pkg);
            if (observation.screen().destination() != NavigationPolicy.Destination.UNKNOWN) return observation;
            Thread.sleep(700);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("START_SCREEN_NOT_RECOGNIZED: no verified signed-in navigation surface");
    }

    private static Observation awaitHome(AndroidDriver driver, String app, String pkg, int seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        do {
            var observation = observe(driver, app, pkg);
            if (observation.screen().destination() == NavigationPolicy.Destination.HOME) return observation;
            Thread.sleep(700);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("HOME_PREPARATION_FAILED");
    }

    private static void click(AndroidDriver driver, String app, String pkg, NavigationPolicy.Control action) throws Exception {
        click(driver, app, pkg, action, null);
    }

    private static void click(AndroidDriver driver, String app, String pkg, NavigationPolicy.Control action,
                              NavigationPolicy.Destination expectedSource) throws Exception {
        var current = observe(driver, app, pkg);
        if (expectedSource != null && current.screen().destination() != expectedSource)
            throw new IllegalStateException("START_SCREEN_CHANGED_BEFORE_TAP");
        if (!NavigationPolicy.routeChoices(current.screen()).contains(action))
            throw new IllegalStateException("REPEATED_OR_CHANGED_CONTROL");
        List<WebElement> matches = driver.findElements(By.id(action.id())).stream()
                .filter(WebElement::isDisplayed).filter(WebElement::isEnabled).toList();
        if (matches.size() != 1 || !action.label().equals(NavigationPolicy.description(matches.get(0).getAttribute("content-desc"))))
            throw new IllegalStateException("AMBIGUOUS_OR_CHANGED_CONTROL");
        matches.get(0).click();
    }

    private static void snapshot(AndroidDriver driver, Path dir, String name) throws Exception {
        Files.writeString(dir.resolve(name + ".xml"), driver.getPageSource(), StandardCharsets.UTF_8);
        Files.write(dir.resolve(name + ".png"), driver.getScreenshotAs(OutputType.BYTES));
    }

    private static void collectAndroidEvidence(String device, String pkg, Path dir, List<String> log) {
        String sdk = System.getenv("ANDROID_HOME");
        if (sdk == null) { log.add("Android diagnostics unavailable: ANDROID_HOME missing"); return; }
        String adb = Path.of(sdk, "platform-tools", "adb.exe").toString();
        for (var entry : Map.of("android-crash.txt", List.of("logcat", "-b", "crash", "-d", "-t", "200"),
                "android-exit-info.txt", List.of("shell", "dumpsys", "activity", "exit-info", pkg)).entrySet()) {
            try {
                List<String> command = new ArrayList<>(List.of(adb, "-s", device));
                command.addAll(entry.getValue());
                Process process = new ProcessBuilder(command).redirectErrorStream(true)
                        .redirectOutput(dir.resolve(entry.getKey()).toFile()).start();
                if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); log.add("Diagnostic timed out: " + entry.getKey()); }
            } catch (Exception ignored) { log.add("Diagnostic unavailable: " + entry.getKey()); }
        }
    }

    private static void verifyModel(String model) throws Exception {
        if (model == null || model.isBlank()) throw new IllegalStateException("LAB_MODEL is missing; set it to an installed local Ollama model. No navigation was attempted");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434/api/tags"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response;
        try {
            response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Local Ollama is unavailable at 127.0.0.1:11434; no navigation was attempted");
        }
        if (response.statusCode() != 200) throw new IllegalStateException("Ollama model listing returned HTTP " + response.statusCode());
        Map<?, ?> data = new Json().toType(response.body(), Map.class);
        if (!(data.get("models") instanceof List<?> models) || models.stream().noneMatch(item ->
                item instanceof Map<?, ?> entry && (model.equals(entry.get("name")) || model.equals(entry.get("model")))))
            throw new IllegalStateException("LAB_MODEL does not match an installed Ollama model");
    }

    private static int askModel(String model, String app, NavigationPolicy.Destination source, NavigationPolicy.Destination goal, List<NavigationPolicy.Control> choices, int step, List<String> log)
            throws Exception {
        // Only navigation labels reach the local model. Screenshots, handles, messages and credentials stay local.
        String prompt = "You are testing navigation in " + app + " on a prelogged Android emulator. " +
                "The requested step is " + source + " to " + goal + ". Choose the observed control that reaches that goal, or stop if unavailable. Only output JSON " +
                "{\"index\":number,\"reason\":string}; index -1 means stop. " +
                "Never seek to view media, engage, send messages, register accounts or enter credentials. " +
                "Step " + step + "; choices (zero-based): " +
                java.util.stream.IntStream.range(0, choices.size())
                        .mapToObj(i -> i + ":" + choices.get(i).label() + " (destination " + choices.get(i).destination() + ")").toList();
        Json json = new Json();
        String request = json.toJson(Map.of("model", model, "stream", false, "think", false,
                "format", "json", "prompt", prompt,
                "options", Map.of("temperature", 0, "num_predict", 128)));
        HttpRequest http = HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434/api/generate"))
                .timeout(Duration.ofSeconds(90)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request)).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(http, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Local Ollama returned HTTP " + response.statusCode());
        Map<?, ?> envelope = json.toType(response.body(), Map.class);
        Map<?, ?> decision = json.toType((String) envelope.get("response"), Map.class);
        if (!(decision.get("index") instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue())
                || number.doubleValue() < -1 || number.doubleValue() >= choices.size())
            throw new IllegalStateException("Model returned a non-integer navigation index");
        int index = number.intValue();
        if (index < -1 || index >= choices.size()) throw new IllegalStateException("Model selected an invalid navigation index");
        log.add("Model decision: " + index + " (" +
                (index == -1 ? "stop" : choices.get(index).label().isEmpty() ? "Return to Home (unlabelled icon)" : choices.get(index).label()) + ")");
        return index;
    }
}
