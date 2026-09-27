import io.appium.java_client.AppiumBy;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Native read-only profile lookup. Never clicks posts, Follow, or login controls. */
public class TikTokUserWorker {
    private static final String PACKAGE = "com.zhiliaoapp.musically";
    private static final String ACTIVITY = "com.ss.android.ugc.aweme.splash.SplashActivity";
    private static final String USERNAME = TikTokTarget.username();
    private static final String HANDLE = "@" + USERNAME;
    private static final Path PROJECT = Path.of(System.getProperty("worker.projectDir", "."))
            .toAbsolutePath().normalize();
    private static final Path SCREENSHOT = PROJECT.resolve("tiktok-user.png");
    private AndroidDriver driver;

    public static void main(String[] args) throws Exception {
        new TikTokUserWorker().run();
    }

    private void run() throws Exception {
        long started = System.nanoTime();
        String failure = null;
        String stage = "connecting to localhost Appium";
        boolean screenshotSaved = false;
        try {
            UiAutomator2Options options = new UiAutomator2Options()
                    .setUdid("emulator-5554").setDeviceName("emulator-5554")
                    .setNoReset(true).setAppPackage(PACKAGE).setAppActivity(ACTIVITY);
            // Establish a session before launching, so startup crashes can be screenshotted.
            options.setCapability("appium:autoLaunch", false);
            driver = new AndroidDriver(URI.create("http://127.0.0.1:4723").toURL(), options);
            driver.setSetting("waitForIdleTimeout", 1000);
            if (!driver.isAppInstalled(PACKAGE)) {
                throw new IllegalStateException("TikTok package is not installed: " + PACKAGE);
            }
            stage = "launching installed TikTok";
            driver.terminateApp(PACKAGE);
            driver.activateApp(PACKAGE);

            stage = "finding TikTok's Search control";
            // Search/profile screens were inaccessible during inspection because TikTok crashed.
            // Discover actual IDs and labels at runtime; do not guess obfuscated TikTok IDs.
            waitForControl("Search", n -> !n.getAttribute("class").equals("android.widget.EditText")).click();
            stage = "entering the exact username";
            WebElement input = waitForNode(n -> n.getAttribute("class").equals("android.widget.EditText"),
                    "one visible search input");
            input.clear();
            input.sendKeys(USERNAME);
            driver.executeScript("mobile: performEditorAction", Map.of("action", "search"));

            stage = "opening the Users search-results tab";
            waitForControl("Users", n -> true).click();
            stage = "opening the exact username from Users results";
            // Only search the Users tab, never the Top/Videos grid or display-name matches.
            WebElement result = waitForNode(n -> label(n).equals(USERNAME) || label(n).equals(HANDLE),
                    "exact username " + USERNAME + " in Users results");
            result.click();

            stage = "verifying the profile's exact @handle";
            new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> {
                List<Element> nodes = snapshot();
                checkBlockers(nodes);
                if (!PACKAGE.equals(driver.getCurrentPackage())) return false;
                boolean profileStats = nodes.stream().anyMatch(n -> label(n).matches("(?i).*\\bfollowers\\b.*"))
                        && nodes.stream().anyMatch(n -> label(n).matches("(?i).*\\bfollowing\\b.*"));
                boolean searchInput = nodes.stream().anyMatch(n -> n.getAttribute("class").equals("android.widget.EditText"));
                if (!profileStats || searchInput) return false;
                for (Element node : nodes) {
                    if (label(node).equals(HANDLE)) {
                        WebElement handle = resolve(node);
                        if (handle.isDisplayed() && (HANDLE.equals(handle.getText())
                                || HANDLE.equals(handle.getAttribute("content-desc")))) return true;
                    }
                }
                return false;
            });
            System.out.println("Verified profile handle: " + HANDLE);
        } catch (Exception e) {
            failure = "While " + stage + ": " + usefulReason(e);
        } finally {
            if (driver != null) {
                try {
                    Files.write(SCREENSHOT, driver.getScreenshotAs(OutputType.BYTES));
                    screenshotSaved = true;
                } catch (Exception e) {
                    failure = append(failure, "Could not save screenshot: " + e.getClass().getSimpleName());
                }
                try {
                    Files.writeString(PROJECT.resolve("tiktok-user-ui.xml"), driver.getPageSource());
                } catch (Exception ignored) {
                    System.out.println("UI hierarchy unavailable; inspect the screenshot for a startup or system blocker.");
                }
                try {
                    driver.quit();
                } catch (Exception e) {
                    failure = append(failure, "Driver cleanup failed: " + e.getClass().getSimpleName());
                }
            }
            System.out.println(failure == null ? "PASS: opened TikTok profile with exact handle " + HANDLE : "FAIL: " + failure);
            System.out.println("Screenshot: " + SCREENSHOT + (screenshotSaved ? "" : " (not saved this run)"));
            System.out.printf(Locale.ROOT, "Duration: %.3f seconds%n", (System.nanoTime() - started) / 1_000_000_000.0);
        }
        if (failure != null) throw new IllegalStateException(failure);
    }

    private WebElement waitForControl(String text, Predicate<Element> extra) {
        return waitForNode(n -> label(n).equals(text) && extra.test(n), "accessible " + text + " control");
    }

    private WebElement waitForNode(Predicate<Element> predicate, String description) {
        return new WebDriverWait(driver, Duration.ofSeconds(30)).withMessage("Missing " + description
                + "; TikTok may have stopped, require onboarding, or have changed its UI").until(d -> {
            List<Element> nodes = snapshot();
            checkBlockers(nodes);
            // This is the actual Android full-screen tutorial ID seen during inspection.
            for (Element n : nodes) {
                if (n.getAttribute("resource-id").equals("com.android.systemui:id/ok") && label(n).equals("Got it")) {
                    resolve(n).click();
                    return null;
                }
            }
            if (!PACKAGE.equals(driver.getCurrentPackage())) return null;
            List<Element> matches = nodes.stream().filter(predicate).toList();
            // Fail closed when a label is ambiguous instead of clicking an unrelated target.
            if (matches.size() != 1) return null;
            WebElement element = resolve(matches.get(0));
            return element.isDisplayed() && element.isEnabled() ? element : null;
        });
    }

    private List<Element> snapshot() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            NodeList nodes = factory.newDocumentBuilder().parse(new InputSource(new StringReader(driver.getPageSource())))
                    .getElementsByTagName("*");
            List<Element> visible = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Element node = (Element) nodes.item(i);
                if ("true".equals(node.getAttribute("displayed"))) visible.add(node);
            }
            return visible;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot inspect native accessibility UI; TikTok may be hung or crashing", e);
        }
    }

    private static void checkBlockers(List<Element> nodes) {
        for (Element node : nodes) {
            String text = label(node).toLowerCase(Locale.ROOT);
            if (text.contains("keeps stopping") || text.contains("isn't responding")) {
                throw new IllegalStateException("TikTok crashed or stopped responding (Android system dialog)");
            }
            if (text.equals("log in to tiktok") || text.equals("sign up for tiktok")
                    || text.equals("log in or sign up")) {
                throw new IllegalStateException("TikTok login/sign-up prompt blocks search; no credentials were entered");
            }
            if (text.contains("verify to continue") || text.contains("drag the slider")
                    || text.contains("no network connection")) {
                throw new IllegalStateException("TikTok verification or network blocker; inspect diagnostic screenshot");
            }
        }
    }

    private WebElement resolve(Element node) {
        // IDs and descriptions come from the live hierarchy, not assumed app versions.
        String id = node.getAttribute("resource-id");
        if (!id.isBlank()) {
            List<WebElement> elements = driver.findElements(By.id(id));
            if (elements.size() == 1) return elements.get(0);
        }
        String description = node.getAttribute("content-desc");
        if (!description.isBlank()) {
            List<WebElement> elements = driver.findElements(AppiumBy.accessibilityId(description));
            if (elements.size() == 1) return elements.get(0);
        }
        String text = node.getAttribute("text");
        // Labels in this flow contain no quotes. Avoid unsafe XPath construction for other text.
        if (text.contains("'") || text.isBlank()) throw new IllegalStateException("No unique accessible locator for control");
        List<WebElement> elements = driver.findElements(By.xpath("//*[@text='" + text + "']"));
        if (elements.size() != 1) throw new IllegalStateException("Ambiguous accessible control; refusing to click");
        return elements.get(0);
    }

    private static String label(Element node) {
        String text = node.getAttribute("text");
        return text.isBlank() ? node.getAttribute("content-desc") : text;
    }

    private static String usefulReason(Exception e) {
        if (e instanceof IllegalStateException) return e.getMessage();
        if (e instanceof org.openqa.selenium.TimeoutException) {
            return "Timed out waiting for the expected accessible UI; inspect the diagnostic screenshot and hierarchy";
        }
        return e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).split("\\R", 2)[0];
    }

    private static String append(String failure, String extra) {
        return failure == null ? extra : failure + "; " + extra;
    }
}
