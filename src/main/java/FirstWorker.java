import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FirstWorker {
    private static final String TARGET = "https://derma-steel-ten.vercel.app";
    private static final String HOST = "derma-steel-ten.vercel.app";
    private static final String HEADING = "Continue your skin journal";

    public static void main(String[] args) {
        long started = System.nanoTime();
        Path screenshot = Path.of(System.getProperty("worker.projectDir", "."))
                .toAbsolutePath().normalize().resolve("derma-test.png");
        String secret = System.getenv("VERCEL_AUTOMATION_BYPASS_SECRET");
        AndroidDriver driver = null;
        String failure = null;
        String stage = "starting Chrome; check the emulator, Appium and ChromeDriver";
        boolean screenshotSaved = false;
        boolean blankScreenshot = false;

        // WebDriver exceptions and transport logs can include navigation arguments.
        Logger.getLogger("org.openqa.selenium").setLevel(Level.OFF);
        Logger.getLogger("io.appium.java_client").setLevel(Level.OFF);
        try {
            if (secret == null || secret.isBlank()) {
                failure = "VERCEL_AUTOMATION_BYPASS_SECRET is missing or blank in the process environment";
            } else {
                UiAutomator2Options options = new UiAutomator2Options();
                options.setUdid("emulator-5554");
                options.setDeviceName("emulator-5554");
                options.setNoReset(true);
                options.setCapability("browserName", "Chrome");
                driver = new AndroidDriver(URI.create("http://127.0.0.1:4723").toURL(), options);
                driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(60));

                stage = "loading the protected deployment; check the bypass secret and network";
                String encodedSecret = URLEncoder.encode(secret, StandardCharsets.UTF_8);
                driver.get(TARGET + "?x-vercel-protection-bypass=" + encodedSecret
                        + "&x-vercel-set-bypass-cookie=true");

                stage = "waiting for the bypass redirect to remove sensitive URL data";
                final String bypassSecret = secret;
                new WebDriverWait(driver, Duration.ofSeconds(30)).until(d ->
                        safeUrl(d.getCurrentUrl(), bypassSecret)
                                && "complete".equals(((JavascriptExecutor) d).executeScript("return document.readyState")));

                URI destination = URI.create(driver.getCurrentUrl());
                if (!HOST.equals(destination.getHost()) || !"https".equals(destination.getScheme())) {
                    failure = "The bypass did not reach the DERMA HTTPS host; check deployment protection and the secret";
                } else {
                    stage = "waiting for the visible DERMA login heading: " + HEADING;
                    new WebDriverWait(driver, Duration.ofSeconds(30)).until(d ->
                            d.findElements(By.cssSelector("h1, h2, h3, h4, h5, h6, [role='heading']"))
                                    .stream().anyMatch(e -> e.isDisplayed() && HEADING.equals(e.getText().strip())));
                    destination = URI.create(driver.getCurrentUrl());
                    if (!HOST.equals(destination.getHost()) || !"https".equals(destination.getScheme())
                            || !safeUrl(driver.getCurrentUrl(), secret)) {
                        failure = "Final destination or URL safety verification failed";
                    }
                }
            }
        } catch (Exception ignored) {
            // Never attach or print the original exception: it may contain the secret URL.
            failure = "Failed while " + stage;
        } finally {
            if (driver != null) {
                try {
                    if (!safeUrl(driver.getCurrentUrl(), secret)) {
                        failure = append(failure, "Sensitive URL remained; original page screenshot withheld");
                        driver.get("about:blank");
                        blankScreenshot = true;
                    }
                    if (!safeUrl(driver.getCurrentUrl(), secret)) {
                        throw new IllegalStateException("Unsafe screenshot URL");
                    }
                    Files.write(screenshot, driver.getScreenshotAs(OutputType.BYTES));
                    screenshotSaved = true;
                } catch (Exception ignored) {
                    failure = append(failure, "Screenshot unavailable or URL could not be confirmed safe");
                } finally {
                    try {
                        driver.quit();
                    } catch (Exception ignored) {
                        failure = append(failure, "Driver cleanup failed");
                    }
                }
            }
            System.out.println(failure == null ? "PASS: DERMA host and login heading verified" : "FAIL: " + failure);
            System.out.println("Screenshot: " + screenshot + (screenshotSaved
                    ? (blankScreenshot ? " (blank diagnostic image to protect the secret)" : "")
                    : " (not saved this run)"));
            System.out.printf(Locale.ROOT, "Duration: %.3f seconds%n", (System.nanoTime() - started) / 1_000_000_000.0);
        }
        // Keep Maven's failure output free of driver exceptions, URLs and page text.
        if (failure != null) throw new IllegalStateException(failure);
    }

    private static String append(String failure, String reason) {
        return failure == null ? reason : failure + "; " + reason;
    }

    private static boolean safeUrl(String url, String secret) {
        if (url == null || url.isBlank()) return false;
        String decoded = url;
        // Also catch nested encoded redirect URLs and encoded secrets.
        for (int i = 0; i < 5; i++) {
            String lower = decoded.toLowerCase(Locale.ROOT);
            if (lower.contains("x-vercel-protection-bypass") || lower.contains("x-vercel-set-bypass-cookie")
                    || (secret != null && !secret.isBlank() && decoded.contains(secret))) return false;
            String next;
            try {
                next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
            if (next.equals(decoded)) return true;
            decoded = next;
        }
        return false;
    }
}
