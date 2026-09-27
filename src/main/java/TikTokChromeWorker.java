import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Reads a public profile only: no credentials, Follow actions, or post interactions. */
public class TikTokChromeWorker {
    private static final String HANDLE = "@" + TikTokTarget.username();
    private static final String TARGET = "https://www.tiktok.com/" + HANDLE;
    // Observed in the mobile Chrome DOM, not inferred from the display name or URL.
    private static final By PROFILE_HANDLE = By.cssSelector("[data-e2e='user-page'] h2[data-e2e='user-title']");
    private static final By NOT_NOW = By.cssSelector("button[data-e2e='alt-middle-cta-cancel-btn']");
    private AndroidDriver driver;
    private String lastObservation = "Page has not rendered";

    public static void main(String[] args) throws Exception {
        new TikTokChromeWorker().run();
    }

    private void run() throws Exception {
        long started = System.nanoTime();
        Path screenshot = Path.of(System.getProperty("worker.projectDir", "."))
                .toAbsolutePath().normalize().resolve("tiktok-chrome.png");
        String failure = null;
        String stage = "starting Chrome through localhost Appium";
        boolean saved = false;
        try {
            UiAutomator2Options options = new UiAutomator2Options()
                    .setUdid("emulator-5554").setDeviceName("emulator-5554").setNoReset(true);
            options.setCapability("browserName", "Chrome");
            driver = new AndroidDriver(URI.create("http://127.0.0.1:4723").toURL(), options);
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(60));
            stage = "loading the TikTok profile";
            driver.get(TARGET);
            stage = "verifying the rendered profile";
            long[] visibleSince = {0};
            new WebDriverWait(driver, Duration.ofSeconds(40))
                    .ignoring(StaleElementReferenceException.class).until(d -> {
                        checkVerificationChallenge();
                        // Dismiss only the observed app-install promotion, never a login or consent dialog.
                        for (WebElement button : driver.findElements(NOT_NOW)) {
                            if (button.isDisplayed() && "Not now".equals(button.getText().strip()) && unobstructed(button)) {
                                button.click();
                                visibleSince[0] = 0;
                                return false;
                            }
                        }
                        if (!profileVisible()) {
                            visibleSince[0] = 0;
                            return false;
                        }
                        if (visibleSince[0] == 0) visibleSince[0] = System.nanoTime();
                        return System.nanoTime() - visibleSince[0] >= Duration.ofSeconds(2).toNanos();
                    });
            if (!profileVisible()) throw new IllegalStateException("Profile changed before capture");
            System.out.println("Verified visible profile handle: " + HANDLE);
        } catch (Exception e) {
            failure = "While " + stage + ": " + e.getClass().getSimpleName() + ". " + lastObservation;
        } finally {
            if (driver != null) {
                try {
                    Files.write(screenshot, driver.getScreenshotAs(OutputType.BYTES));
                    saved = true;
                } catch (Exception e) {
                    failure = append(failure, "Screenshot failed: " + e.getClass().getSimpleName());
                } finally {
                    try {
                        driver.quit();
                    } catch (Exception e) {
                        failure = append(failure, "Driver cleanup failed: " + e.getClass().getSimpleName());
                    }
                }
            }
            System.out.println(failure == null ? "PASS: visible TikTok profile handle is exactly " + HANDLE : "FAIL: " + failure);
            System.out.println("Screenshot: " + screenshot + (saved ? "" : " (not saved this run)"));
            System.out.printf(Locale.ROOT, "Duration: %.3f seconds%n", (System.nanoTime() - started) / 1_000_000_000.0);
        }
        if (failure != null) throw new IllegalStateException(failure);
    }

    private boolean profileVisible() {
        checkVerificationChallenge();
        URI destination = URI.create(driver.getCurrentUrl());
        if (!"https".equals(destination.getScheme()) || !"www.tiktok.com".equals(destination.getHost())
                || !("/" + HANDLE).equals(destination.getPath())) {
            lastObservation = "Redirected away from the requested profile (possible login or error page)";
            return false;
        }
        if (!"complete".equals(driver.executeScript("return document.readyState"))) {
            lastObservation = "Page is still loading";
            return false;
        }
        for (WebElement heading : driver.findElements(By.cssSelector("h1,h2,[role='dialog'],[aria-modal='true']"))) {
            if (!heading.isDisplayed()) continue;
            String text = heading.getText().toLowerCase(Locale.ROOT);
            if (text.contains("log in to tiktok") || text.contains("sign up for tiktok")
                    || text.contains("couldn't find this account") || text.contains("something went wrong")
                    || text.contains("verify to continue") || text.contains("access denied")) {
                lastObservation = "A visible login, verification, or error prompt blocks profile verification";
                return false;
            }
        }
        List<WebElement> handles = driver.findElements(PROFILE_HANDLE);
        if (handles.size() != 1 || !handles.get(0).isDisplayed()) {
            lastObservation = "No unique visible profile handle; possible login, unavailable account, or changed page";
            return false;
        }
        WebElement handle = handles.get(0);
        if (!HANDLE.equals(handle.getText().strip())) {
            lastObservation = "The rendered profile handle does not exactly match " + HANDLE;
            return false;
        }
        // isDisplayed alone permits text behind modal overlays; check the actual hit target too.
        if (!unobstructed(handle)) {
            lastObservation = "The profile handle is obscured by a prompt or overlay";
            return false;
        }
        for (String key : List.of("followers-count", "following-count")) {
            List<WebElement> stats = driver.findElements(By.cssSelector("[data-e2e='user-page'] [data-e2e='" + key + "']"));
            if (stats.size() != 1 || !stats.get(0).isDisplayed() || stats.get(0).getText().isBlank()
                    || !unobstructed(stats.get(0))) {
                lastObservation = "Profile statistics are missing or obscured";
                return false;
            }
        }
        lastObservation = "Exact visible handle and profile statistics verified";
        return true;
    }

    private void checkVerificationChallenge() {
        if (challengeTextVisible()) verificationBlocked();
        for (WebElement frame : driver.findElements(By.tagName("iframe"))) {
            if (!frame.isDisplayed()) continue;
            String description = (String.valueOf(frame.getAttribute("src")) + " "
                    + frame.getAttribute("title") + " " + frame.getAttribute("id")).toLowerCase(Locale.ROOT);
            if (description.contains("captcha") || description.contains("verify")) verificationBlocked();
            try {
                driver.switchTo().frame(frame);
                if (challengeTextVisible()) verificationBlocked();
            } finally {
                driver.switchTo().defaultContent();
            }
        }
    }

    private boolean challengeTextVisible() {
        for (WebElement body : driver.findElements(By.tagName("body"))) {
            String text = body.getText().toLowerCase(Locale.ROOT);
            if (text.contains("drag the slider to fit the puzzle") || text.contains("verify to continue")) return true;
        }
        return false;
    }

    private void verificationBlocked() {
        lastObservation = "TikTok verification/CAPTCHA challenge blocks the profile; no challenge interaction was attempted";
        throw new IllegalStateException(lastObservation);
    }

    private boolean unobstructed(WebElement element) {
        return Boolean.TRUE.equals(driver.executeScript("""
                const e = arguments[0], r = e.getBoundingClientRect();
                const x = r.left + r.width / 2, y = r.top + r.height / 2;
                if (!r.width || !r.height || x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) return false;
                const top = document.elementFromPoint(x, y);
                return top === e || e.contains(top);
                """, element));
    }

    private static String append(String failure, String extra) {
        return failure == null ? extra : failure + "; " + extra;
    }
}
