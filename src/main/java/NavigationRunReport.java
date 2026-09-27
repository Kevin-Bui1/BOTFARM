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

    public NavigationRunReport(String app, String device, String goal, Path directory) {
        this.app = app; this.device = device; this.goal = goal; this.directory = directory;
    }

    public void action(String phase, NavigationPolicy.Control control) {
        actions.add(Map.of("phase", phase, "label", control.label().isEmpty() ? "Return to Home (unlabelled icon)" : control.label(),
                "locator", control.id(), "destination", control.destination().name()));
    }

    public void verified(String phase, NavigationPolicy.Screen screen, String screenshot) {
        screens.add(Map.of("phase", phase, "screen", screen.destination().name(),
                "markers", new TreeSet<>(screen.markers()), "screenshot", screenshot));
    }

    public void write(boolean passed, String reason, int taps, List<String> log) throws Exception {
        var screenshots = new ArrayList<String>();
        for (String name : List.of("launch.png", "before.png", "after.png", "failure.png"))
            if (Files.isRegularFile(directory.resolve(name))) screenshots.add(name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("app", app); result.put("device", device); result.put("goal", "home-to-" + goal);
        result.put("passed", passed); result.put("reason", reason); result.put("requestedDestination", goal.toUpperCase(Locale.ROOT));
        result.put("destinationTaps", taps); result.put("actions", actions); result.put("verifiedScreens", screens);
        result.put("screenshots", screenshots); result.put("observations", List.copyOf(log));
        Files.writeString(directory.resolve("result.json"), new Json().toJson(result), StandardCharsets.UTF_8);
        StringBuilder md = new StringBuilder("# Navigation test report\n\n");
        md.append("- App: **").append(escape(app)).append("**\n- Device: `").append(escape(device))
                .append("`\n- Goal: **Home → ").append(escape(goal)).append("**\n- Result: **")
                .append(passed ? "PASS" : "FAIL").append("**\n- Stop reason: ").append(escape(reason))
                .append("\n- Destination taps: ").append(taps).append("\n\n## Actions taken\n\n");
        if (actions.isEmpty()) md.append("No navigation actions were taken.\n");
        else {
            md.append("| Phase | Observed control | Intended screen |\n|---|---|---|\n");
            for (var action : actions) md.append("| ").append(escape(action.get("phase"))).append(" | ")
                    .append(escape(action.get("label"))).append(" | ").append(action.get("destination")).append(" |\n");
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
}
