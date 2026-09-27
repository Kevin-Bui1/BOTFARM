import io.appium.java_client.AppiumBy;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.json.Json;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
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

public class SocialAppLabWorker {
    private record Choice(String label, String locatorKind, String locator) {
        By by() { return locatorKind.equals("id") ? By.id(locator) : AppiumBy.accessibilityId(locator); }
    }

    // Exact navigation labels only. Content tiles, videos, reactions and composer controls are excluded.
    private static final Set<String> NAVIGATION = Set.of(
            "home", "search", "discover", "explore", "profile", "me", "settings", "settings and privacy",
            "your profile", "back");
    private static final String BLOCKERS = "captcha|verify to continue|verify you're human|security check|" +
            "suspicious activity|keeps stopping|isn't responding|log in to tiktok|sign up for tiktok|" +
            "log in or sign up|enter your password|create account";

    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();
        if (args.length != 1 || !(args[0].equals("tiktok") || args[0].equals("instagram"))) {
            throw new IllegalArgumentException("Usage: SocialAppLabWorker tiktok|instagram");
        }
        String app = args[0];
        String pkg = app.equals("tiktok") ? "com.zhiliaoapp.musically" : "com.instagram.android";
        String device = System.getenv().getOrDefault("LAB_DEVICE", "emulator-5554");
        String model = System.getenv("LAB_MODEL");
        Path dir = Path.of(System.getProperty("worker.projectDir", "."), "lab-runs",
                app + "-" + Instant.now().toString().replace(':', '-')).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        List<String> log = new ArrayList<>();
        log.add("App: " + app + "; device: " + device + "; start: " + Instant.now());
        AndroidDriver driver = null;
        boolean passed = false;
        int taps = 0;
        String stage = "verifying local Ollama model";
        try {
            verifyModel(model);
            log.add("Installed local Ollama model verified: " + model);
            stage = "creating native UiAutomator2 session";
            UiAutomator2Options options = new UiAutomator2Options()
                    .setUdid(device).setDeviceName(device).setNoReset(true);
            options.setCapability("appium:autoLaunch", false);
            options.setCapability("appium:newCommandTimeout", 180);
            driver = new AndroidDriver(URI.create("http://127.0.0.1:4723").toURL(), options);
            driver.setSetting("waitForIdleTimeout", 1000);
            if (!driver.isAppInstalled(pkg)) throw new IllegalStateException("Install " + app + " on " + device + " first");
            stage = "launching TikTok and waiting for its native accessibility UI";
            driver.activateApp(pkg);
            Files.write(dir.resolve("launch.png"), driver.getScreenshotAs(OutputType.BYTES));
            final AndroidDriver activeDriver = driver;
            String launchXml = new WebDriverWait(driver, Duration.ofSeconds(45))
                    .ignoring(WebDriverException.class).until(d -> {
                        String source = activeDriver.getPageSource();
                        checkScreen(activeDriver, pkg, source);
                        return source.contains("text=\"") ? source : null;
                    });
            log.add("Foreground package after launch: " + driver.getCurrentPackage());
            checkScreen(driver, pkg, launchXml);
            log.add("Native app launched with existing data preserved");
            Set<String> visited = new HashSet<>();
            for (int step = 0; step < 5; step++) {
                stage = "observing navigation step " + step;
                Files.write(dir.resolve("step-" + step + ".png"), driver.getScreenshotAs(OutputType.BYTES));
                String xml = driver.getPageSource();
                checkScreen(driver, pkg, xml);
                List<Choice> choices = choices(driver, xml);
                String fingerprint = choices.toString();
                log.add("Step " + step + " navigation: " + choices.stream().map(Choice::label).toList());
                if (choices.isEmpty() || !visited.add(fingerprint)) {
                    log.add("Stopped: no new navigation choices");
                    break;
                }
                int selected = askModel(model, app, choices, step, log);
                if (selected < 0) { log.add("Model chose STOP"); break; }
                Choice action = choices.get(selected);
                // Recheck the locator and foreground immediately before the tap.
                if (!pkg.equals(driver.getCurrentPackage())) throw new IllegalStateException("App left foreground; stopping");
                List<WebElement> matches = driver.findElements(action.by()).stream()
                        .filter(WebElement::isDisplayed).filter(WebElement::isEnabled).toList();
                if (matches.size() != 1) throw new IllegalStateException("Navigation target changed or became ambiguous");
                checkScreen(driver, pkg, driver.getPageSource());
                WebElement target = matches.get(0);
                String description = target.getAttribute("content-desc");
                String label = description != null && !description.isBlank() ? description.strip() : target.getText().strip();
                if (!label.equals(action.label()) || !NAVIGATION.contains(label.toLowerCase(Locale.ROOT)))
                    throw new IllegalStateException("Navigation label changed; refusing action");
                matches.get(0).click();
                taps++;
                log.add("Tapped: " + action.label());
                Thread.sleep(1200);
            }
            Files.write(dir.resolve("final.png"), driver.getScreenshotAs(OutputType.BYTES));
            checkScreen(driver, pkg, driver.getPageSource());
            if (taps == 0) throw new IllegalStateException("Observation completed but no navigation was exercised");
            log.add("Completed bounded navigation test");
            passed = true;
        } catch (Exception e) {
            log.add("Stopped while " + stage + ": " + e.getClass().getSimpleName() + ": " +
                    (e instanceof IllegalStateException ? String.valueOf(e.getMessage()) : "native UI unavailable, app stalled, or service request failed; inspect screenshot and Appium console"));
            if (driver != null) {
                try { Files.write(dir.resolve("failure.png"), driver.getScreenshotAs(OutputType.BYTES)); }
                catch (Exception ignored) { log.add("Failure screenshot unavailable"); }
            }
        } finally {
            if (driver != null) try { driver.quit(); } catch (Exception ignored) { passed = false; log.add("Driver cleanup failed"); }
            log.add((passed ? "PASS" : "FAIL") + ": navigation taps completed: " + taps);
            log.add(String.format(Locale.ROOT, "Duration: %.3f seconds", (System.nanoTime() - started) / 1_000_000_000.0));
            Files.write(dir.resolve("report.txt"), log, StandardCharsets.UTF_8);
            System.out.println("Report: " + dir.resolve("report.txt").toAbsolutePath());
            log.forEach(System.out::println);
        }
        if (!passed) throw new IllegalStateException("Social app lab run failed; inspect " + dir.resolve("report.txt"));
    }

    private static void checkScreen(AndroidDriver driver, String pkg, String xml) {
        if (xml.toLowerCase(Locale.ROOT).matches("(?s).*\\b(" + BLOCKERS + ")\\b.*"))
            throw new IllegalStateException("Login, verification, crash or security prompt detected; inspect screenshot");
        if (!pkg.equals(driver.getCurrentPackage())) throw new IllegalStateException("App left foreground; stopping");
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

    private static List<Choice> choices(AndroidDriver driver, String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        NodeList nodes = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getElementsByTagName("*");
        List<Choice> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (!"true".equals(node.getAttribute("displayed"))) continue;
            String text = node.getAttribute("text").strip();
            String description = node.getAttribute("content-desc").strip();
            String label = !description.isBlank() ? description : text;
            if (!NAVIGATION.contains(label.toLowerCase(Locale.ROOT))) continue;
            String id = node.getAttribute("resource-id");
            String kind = !id.isBlank() ? "id" : "accessibility";
            String locator = !id.isBlank() ? id : description;
            if (locator.isBlank() || !seen.add(kind + ":" + locator)) continue;
            By by = kind.equals("id") ? By.id(locator) : AppiumBy.accessibilityId(locator);
            List<WebElement> matches = driver.findElements(by).stream()
                    .filter(WebElement::isDisplayed).filter(WebElement::isEnabled).toList();
            if (matches.size() == 1) result.add(new Choice(label, kind, locator));
        }
        return result;
    }

    private static int askModel(String model, String app, List<Choice> choices, int step, List<String> log)
            throws Exception {
        // Only navigation labels reach the local model. Screenshots, handles, messages and credentials stay local.
        String prompt = "You are testing navigation in " + app + " on a prelogged Android emulator. " +
                "Select one useful navigation control to inspect, or stop. Only output JSON " +
                "{\"index\":number,\"reason\":string}; index -1 means stop. " +
                "Never seek to view media, engage, send messages, register accounts or enter credentials. " +
                "Step " + step + "; choices (zero-based): " +
                java.util.stream.IntStream.range(0, choices.size())
                        .mapToObj(i -> i + ":" + choices.get(i).label()).toList();
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
                (index == -1 ? "stop" : choices.get(index).label()) + ")");
        return index;
    }
}
