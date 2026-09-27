import org.openqa.selenium.json.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Human and machine reports share the same action and verification evidence. */
public final class NavigationRunReport {
    private final String app, device, goal;
    private final Path directory;
    private final List<Map<String, Object>> actions = new ArrayList<>();
    private final List<Map<String, Object>> screens = new ArrayList<>();
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private List<String> route = List.of();
    private int stoppedAtStep;
    private String stopStage = "";

    public NavigationRunReport(String app, String device, String goal, Path directory) {
        this.app = app; this.device = device; this.goal = goal; this.directory = directory;
    }

    public void action(String phase, NavigationPolicy.Control control) {
        actions.add(Map.of("phase", phase, "label", control.label().isEmpty() ? "Return to Home (unlabelled icon)" : control.label(),
                "locator", control.id(), "destination", control.destination().name()));
    }

    public void route(List<NavigationPolicy.Destination> value) {
        route = value.stream().map(Enum::name).toList();
    }

    public void stopContext(int step, String stage) { stoppedAtStep = step; stopStage = stage; }

    public void beginStep(int number, NavigationPolicy.Destination source, NavigationPolicy.Control action, String before) {
        var step = new LinkedHashMap<String, Object>();
        step.put("step", number); step.put("source", source.name()); step.put("requestedDestination", action.destination().name());
        step.put("control", action.label().isEmpty() ? "Return to Home (unlabelled icon)" : action.label());
        step.put("locator", action.id()); step.put("beforeScreenshot", before); step.put("afterScreenshot", "");
        step.put("verifiedDestination", ""); step.put("result", "PENDING");
        steps.add(step);
    }

    public void finishStep(int number, NavigationPolicy.Destination destination, String after, String verdict) {
        var step = steps.get(number - 1);
        step.put("verifiedDestination", destination.name()); step.put("afterScreenshot", after); step.put("result", verdict);
    }

    public void failStep(int number, String reason) {
        if (number > 0 && number <= steps.size() && steps.get(number - 1).get("result").equals("PENDING")) {
            var step = steps.get(number - 1);
            step.put("result", reason);
            step.put("afterScreenshot", "failure.png");
        }
    }

    public void verified(String phase, NavigationPolicy.Screen screen, String screenshot) {
        screens.add(Map.of("phase", phase, "screen", screen.destination().name(),
                "markers", new TreeSet<>(screen.markers()), "screenshot", screenshot));
    }

    public void write(boolean passed, String reason, int taps, List<String> log) throws Exception {
        var screenshots = new ArrayList<String>();
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("(launch|before|after|failure|preparation-(before|after)|step-[0-9]+-(before|after))\\.png"))
                    .sorted().forEach(screenshots::add);
        }
        if (passed && !route.isEmpty()) {
            if (steps.size() != route.size() - 1) throw new IllegalStateException("REPORT_INCOMPLETE_ROUTE");
            for (int i = 0; i < steps.size(); i++) {
                var step = steps.get(i);
                if (!step.get("result").equals("VERIFIED") || !step.get("source").equals(route.get(i))
                        || !step.get("verifiedDestination").equals(route.get(i + 1))
                        || !step.get("requestedDestination").equals(route.get(i + 1))
                        || !screenshots.contains(step.get("beforeScreenshot")) || !screenshots.contains(step.get("afterScreenshot")))
                    throw new IllegalStateException("REPORT_UNVERIFIED_ROUTE_STEP");
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("app", app); result.put("device", device); result.put("goal", "home-to-" + goal);
        result.put("passed", passed); result.put("reason", reason);
        result.put("requestedDestination", route.isEmpty() ? goal.toUpperCase(Locale.ROOT) : route.get(route.size() - 1));
        result.put("route", route); result.put("steps", steps);
        result.put("stoppedAtStep", passed ? null : stoppedAtStep); result.put("stopStage", stopStage);
        result.put("destinationTaps", taps); result.put("actions", actions); result.put("verifiedScreens", screens);
        result.put("screenshots", screenshots); result.put("observations", List.copyOf(log));
        Files.writeString(directory.resolve("result.json"), new Json().toJson(result), StandardCharsets.UTF_8);
        StringBuilder md = new StringBuilder("# Navigation test report\n\n");
        md.append("- App: **").append(escape(app)).append("**\n- Device: `").append(escape(device))
                .append("`\n- Goal: **").append(escape(goal)).append("**\n- Route: ")
                .append(escape(String.join(" → ", route))).append("\n- Result: **")
                .append(passed ? "PASS" : "FAIL").append("**\n- Stop reason: ").append(escape(reason))
                .append("\n- Destination taps: ").append(taps).append("\n\n## Actions taken\n\n");
        if (!passed && !stopStage.isEmpty()) md.append("Stopped at route step ").append(stoppedAtStep)
                .append(" (0 means setup), while ").append(escape(stopStage)).append(".\n\n");
        if (actions.isEmpty()) md.append("No navigation actions were taken.\n");
        else {
            md.append("| Phase | Observed control | Intended screen |\n|---|---|---|\n");
            for (var action : actions) md.append("| ").append(escape(action.get("phase"))).append(" | ")
                    .append(escape(action.get("label"))).append(" | ").append(action.get("destination")).append(" |\n");
        }
        md.append("\n## Step trace\n\n");
        if (steps.isEmpty()) md.append("No route controls were tapped.\n");
        else {
            md.append("| Step | From | Observed control | Requested | Verified | Result | Before | After |\n|---|---|---|---|---|---|---|---|\n");
            for (var step : steps) md.append("| ").append(step.get("step")).append(" | ").append(step.get("source"))
                    .append(" | ").append(escape(step.get("control"))).append(" | ").append(step.get("requestedDestination"))
                    .append(" | ").append(step.get("verifiedDestination")).append(" | ").append(escape(step.get("result")))
                    .append(" | ").append(screenshotLink(step.get("beforeScreenshot"), screenshots))
                    .append(" | ").append(screenshotLink(step.get("afterScreenshot"), screenshots)).append(" |\n");
        }
        md.append("\n## Verified screens\n\n");
        if (screens.isEmpty()) md.append("No screen satisfied the deterministic verifier.\n");
        for (var screen : screens) {
            md.append("- **").append(screen.get("screen")).append("** (").append(screen.get("phase")).append(") — ")
                    .append(escape(screen.get("markers"))).append(". ");
            if (screenshots.contains(screen.get("screenshot"))) md.append("[Screenshot](").append(screen.get("screenshot")).append(")");
            else md.append("Screenshot unavailable.");
            md.append('\n');
        }
        md.append("\n## Screenshots and details\n\n");
        for (String file : screenshots) md.append("- [").append(file).append("](").append(file).append(")\n");
        md.append("- [Full action/observation log](report.txt)\n- [Structured result](result.json)\n");
        Files.writeString(directory.resolve("report.md"), md, StandardCharsets.UTF_8);
    }

    private static String escape(Object value) {
        return String.valueOf(value).replace("|", "\\|").replace("\r", " ").replace("\n", " ")
                .replace("<", "&lt;").replace(">", "&gt;").replace("`", "'");
    }

    private static String screenshotLink(Object value, List<String> screenshots) {
        return screenshots.contains(value) ? "[Screenshot](" + value + ")" : "Unavailable";
    }
}
