import java.util.*;

/** Dependency-free regression runner; invoked by verify-navigation.ps1, not by Maven Surefire. */
public final class NavigationPolicyTest {
    private static int assertions;
    private static final String IG = "com.instagram.android:id/";
    private static String node(String id, String attributes, String body) {
        return "<node package='com.instagram.android' displayed='true' resource-id='" + IG + id + "' " + attributes + ">" + body + "</node>";
    }
    private static String tab(String id, String label, boolean selected, boolean childSelected) {
        return node(id, "enabled='true' clickable='true' content-desc='" + label + "' selected='" + selected + "'",
                childSelected ? node("tab_icon", "selected='true'", "") : "");
    }
    private static String screen(String selected, String content) {
        return "<hierarchy>" + content + node("tab_bar", "", tab("feed_tab", "Home", false, selected.equals("HOME"))
                + tab("search_tab", "Search and explore", selected.equals("SEARCH"), false)
                + tab("profile_tab", "Profile", selected.equals("PROFILE"), false)) + "</hierarchy>";
    }
    private static String marker(String id) { return node(id, "", ""); }
    private static NavigationPolicy.Screen observe(String xml) throws Exception { return NavigationPolicy.observe("instagram", xml); }
    private static void eq(Object expected, Object actual, String scenario) {
        assertions++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(scenario + ": expected " + expected + ", got " + actual);
    }
    private static String ttNode(String id, String attributes, String body) {
        return "<node package='com.zhiliaoapp.musically' displayed='true' resource-id='com.zhiliaoapp.musically:id/" + id + "' " + attributes + ">" + body + "</node>";
    }
    private static String ttScreen(boolean profile) {
        String content = profile
                ? ttNode("t3y", "text='@fixture'", "") + ttNode("t5q", "",
                    ttNode("t89", "text='Following'", "") + ttNode("t89", "text='Followers'", "") + ttNode("t89", "text='Likes'", ""))
                : ttNode("long_press_layout", "content-desc='Video'", "") + ttNode("", "content-desc='For You' selected='true'", "");
        return "<hierarchy>" + content + ttNode("omy", "",
                ttNode("omq", "content-desc='Home' clickable='true' enabled='true' selected='" + !profile + "'", "")
                + ttNode("oms", "content-desc='Profile' clickable='true' enabled='true' selected='" + profile + "'", "")) + "</hierarchy>";
    }
    private static String ttSearchForm() {
        return ttNode("vd5", "", ttNode("hu0", "enabled='true' class='android.widget.EditText'", "")
                + ttNode("tv_search_textview", "enabled='true' class='android.widget.Button' text='Search'", "")
                + ttNode("bs5", "enabled='true' clickable='true' content-desc=''", ""));
    }
    private static void rejects(String reason, Runnable operation) {
        try { operation.run(); } catch (IllegalStateException e) {
            eq(true, e.getMessage().startsWith(reason), "typed rejection " + reason); return;
        }
        throw new AssertionError("Expected rejection: " + reason);
    }
    public static void main(String[] args) throws Exception {
        String homeXml = screen("HOME", marker("reels_tray_container"));
        String profileXml = screen("PROFILE", marker("profile_header_container") + marker("row_profile_header"));
        String searchXml = screen("SEARCH", marker("explore_action_bar"));
        var home = observe(homeXml);
        var profile = observe(profileXml);
        var search = observe(searchXml);
        eq(NavigationPolicy.Destination.HOME, home.destination(), "selected state inherited from visible tab child");
        eq(2, NavigationPolicy.choices(home).size(), "only observed non-home destinations offered");
        var requested = NavigationPolicy.choices(home).stream().filter(c -> c.destination() == NavigationPolicy.Destination.PROFILE).findFirst().orElseThrow();
        var searchRequest = NavigationPolicy.choices(home).stream().filter(c -> c.destination() == NavigationPolicy.Destination.SEARCH).findFirst().orElseThrow();
        eq("VERIFIED", NavigationPolicy.transition(home, requested, profile), "real profile transition");
        eq("VERIFIED", NavigationPolicy.transition(home, searchRequest, search), "real search transition");
        eq("SAME_SCREEN", NavigationPolicy.transition(home, requested, home), "tap alone never passes");
        eq("SAME_SCREEN", NavigationPolicy.transition(home, requested, observe(homeXml.replace("</hierarchy>", "<node text='updated feed content'/></hierarchy>"))), "dynamic feed changes do not prove navigation");
        eq("WRONG_DESTINATION", NavigationPolicy.transition(home, requested, search), "different but wrong destination");
        eq("DESTINATION_NOT_VERIFIED", NavigationPolicy.transition(home, requested, observe(screen("PROFILE", marker("reels_tray_container")))), "selected tab alone is insufficient");
        eq("DESTINATION_NOT_VERIFIED", NavigationPolicy.transition(home, requested, observe(screen("", marker("profile_header_container") + marker("row_profile_header")))), "content alone is insufficient");
        eq("START_SCREEN_NOT_VERIFIED_HOME", NavigationPolicy.transition(profile, requested, profile), "starting on destination is not a transition");
        var homeControl = home.controls().stream().filter(c -> c.destination() == NavigationPolicy.Destination.HOME).findFirst().orElseThrow();
        eq("REPEATED_OR_UNOBSERVED_CONTROL", NavigationPolicy.transition(home, homeControl, home), "repeated Home rejected");
        eq("REPEATED_OR_UNOBSERVED_CONTROL", NavigationPolicy.transition(home, new NavigationPolicy.Control("Back", "android:id/back", NavigationPolicy.Destination.PROFILE, false), profile), "unobserved Back rejected");
        eq(0, NavigationPolicy.choices(profile).size(), "no destination requested from non-home");
        String duplicate = homeXml.replace("</hierarchy>", node("tab_bar", "", tab("profile_tab", "Profile", false, false)) + "</hierarchy>");
        eq(1, NavigationPolicy.choices(observe(duplicate)).size(), "duplicate targets excluded");
        eq(0, NavigationPolicy.choices(observe(homeXml.replace("id/tab_bar", "id/not_navigation"))).size(), "labels outside tab bar cannot authorize actions");
        eq(NavigationPolicy.Destination.UNKNOWN, observe(homeXml.replace("selected='false'", "selected='true'")).destination(), "contradictory selected tabs rejected");
        eq(NavigationPolicy.Destination.UNKNOWN, observe("<hierarchy><node displayed='false'>" + homeXml.replace("<hierarchy>", "").replace("</hierarchy>", "") + "</node></hierarchy>").destination(), "hidden ancestor rejected");
        eq(NavigationPolicy.Destination.UNKNOWN, observe(screen("PROFILE", marker("profile_header_container"))).destination(), "partial profile content rejected");
        eq("MANUAL_SIGN_IN_REQUIRED", observe("<hierarchy><node text='Join Instagram'/></hierarchy>").blocker(), "Instagram sign-in");
        eq("MANUAL_SIGN_IN_REQUIRED", NavigationPolicy.observe("tiktok", "<hierarchy><node text='Log in to TikTok'/></hierarchy>").blocker(), "TikTok sign-in");
        eq("MANUAL_SIGN_IN_REQUIRED", observe("<hierarchy><node password='true'/></hierarchy>").blocker(), "password form");
        eq("VERIFICATION_REQUIRED", observe("<hierarchy><node text='Security check'/></hierarchy>").blocker(), "verification prompt");
        eq("APP_CRASH_OR_ANR_PROMPT", observe("<hierarchy><node text='TikTok keeps stopping'/></hierarchy>").blocker(), "crash prompt");
        eq("SYSTEM_PERMISSION_PROMPT", observe("<hierarchy><node package='com.google.android.permissioncontroller' resource-id='com.android.permissioncontroller:id/permission_message' text='Allow notifications?'/></hierarchy>").blocker(), "observed OS permission prompt stops without changing settings");
        var blocked = observe(profileXml.replace("</hierarchy>", "<node text='Log in'/></hierarchy>"));
        eq("MANUAL_SIGN_IN_REQUIRED", NavigationPolicy.transition(home, requested, blocked), "login overlay overrides destination markers");
        eq(NavigationPolicy.Destination.UNKNOWN, NavigationPolicy.observe("tiktok", homeXml).destination(), "unknown app layout cannot borrow Instagram verification");
        eq("MANUAL_AGE_VERIFICATION_REQUIRED", NavigationPolicy.observe("tiktok", "<hierarchy><node text=\"When\u2019s your birthdate?\"/></hierarchy>").blocker(), "age verification requires user input");
        String duplicateDestination = profileXml.replace("</hierarchy>", node("tab_bar", "", tab("profile_tab", "Profile", true, false)) + "</hierarchy>");
        eq("AMBIGUOUS_DESTINATION", NavigationPolicy.transition(home, requested, observe(duplicateDestination)), "duplicate destination rejected");
        var ttHome = NavigationPolicy.observe("tiktok", ttScreen(false));
        var ttProfile = NavigationPolicy.observe("tiktok", ttScreen(true));
        eq(NavigationPolicy.Destination.HOME, ttHome.destination(), "TikTok observed Home structure");
        eq(NavigationPolicy.Destination.PROFILE, ttProfile.destination(), "TikTok own profile content");
        eq(1, NavigationPolicy.choices(ttHome).size(), "only verified TikTok destination offered");
        var ttRequested = NavigationPolicy.choices(ttHome).get(0);
        eq("VERIFIED", NavigationPolicy.transition(ttHome, ttRequested, ttProfile), "TikTok Home to own Profile");
        eq("SAME_SCREEN", NavigationPolicy.transition(ttHome, ttRequested, ttHome), "TikTok ignored tap cannot pass");
        eq(NavigationPolicy.Destination.UNKNOWN, NavigationPolicy.observe("tiktok", ttScreen(true).replace("@fixture", "")).destination(), "TikTok account marker required");
        eq(NavigationPolicy.Destination.UNKNOWN, NavigationPolicy.observe("tiktok", ttScreen(true).replace("text='Followers'", "text='Other'")).destination(), "TikTok all profile metrics required");
        eq(NavigationPolicy.Destination.UNKNOWN, NavigationPolicy.observe("tiktok", ttScreen(true).replace("id/t5q", "id/other")).destination(), "TikTok metrics require profile container");
        eq(NavigationPolicy.Destination.UNKNOWN, NavigationPolicy.observe("tiktok", ttScreen(true).replace("id/omy", "id/other")).destination(), "TikTok controls require observed navigation bar");
        eq(NavigationPolicy.Destination.UNKNOWN, NavigationPolicy.observe("instagram", ttScreen(true)).destination(), "TikTok markers cannot verify Instagram");
        var ttLogin = NavigationPolicy.observe("tiktok", ttScreen(true).replace("</hierarchy>", "<node text='Log in to TikTok'/></hierarchy>"));
        eq("MANUAL_SIGN_IN_REQUIRED", NavigationPolicy.transition(ttHome, ttRequested, ttLogin), "TikTok login overlay overrides profile");
        var ttTutorial = NavigationPolicy.observe("tiktok", ttScreen(false).replace("</hierarchy>", ttNode("tv_strengthen_swipe_up_guide", "text='Swipe up for more'", "") + "</hierarchy>"));
        eq("ONBOARDING_NAVIGATION_REQUIRED", ttTutorial.blocker(), "TikTok tutorial accurately blocks action");
        eq(0, NavigationPolicy.choices(ttTutorial).size(), "no actions while onboarding intercepts tabs");
        eq(NavigationPolicy.Destination.SEARCH, NavigationPolicy.goal("instagram", "search"), "explicit Instagram goal");
        eq(NavigationPolicy.Destination.PROFILE, NavigationPolicy.goal("instagram", "profile"), "new Instagram goal");
        eq(NavigationPolicy.Destination.SEARCH, NavigationPolicy.goal("tiktok", "search"), "new TikTok goal");
        rejects("UNSUPPORTED_GOAL", () -> NavigationPolicy.goal("tiktok", "inbox"));
        rejects("UNSUPPORTED_GOAL", () -> NavigationPolicy.goal("other", "profile"));
        rejects("MODEL_GOAL_MISMATCH", () -> NavigationPolicy.requireGoal(NavigationPolicy.Destination.SEARCH, requested));
        NavigationPolicy.requireGoal(NavigationPolicy.Destination.PROFILE, requested);
        String ttSearchControl = ttNode("uvy", "", ttNode("k_8", "content-desc='Search' clickable='true' enabled='true'", ""));
        String ttBothXml = ttScreen(false).replace("</hierarchy>", ttSearchControl + "</hierarchy>");
        var ttBoth = NavigationPolicy.observe("tiktok", ttBothXml);
        eq(2, NavigationPolicy.choices(ttBoth).size(), "LLM sees both observed TikTok destinations");
        var ttSearchAction = NavigationPolicy.choices(ttBoth).stream().filter(c -> c.destination() == NavigationPolicy.Destination.SEARCH).findFirst().orElseThrow();
        String overlayXml = ttBothXml.replace("</hierarchy>", ttSearchForm() + "</hierarchy>");
        var overlay = NavigationPolicy.observe("tiktok", overlayXml);
        eq(NavigationPolicy.Destination.SEARCH, overlay.destination(), "search overlay supersedes selected background Home");
        eq("VERIFIED", NavigationPolicy.transition(ttBoth, ttSearchAction, overlay), "Search form transition without persistent selected tab");
        eq("WRONG_DESTINATION", NavigationPolicy.transition(ttBoth, ttSearchAction, ttProfile), "Profile cannot satisfy requested Search");
        eq(1, overlay.controls().size(), "Search only exposes return control, never submit");
        eq("com.zhiliaoapp.musically:id/bs5", overlay.controls().get(0).id(), "observed back icon for preparation");
        eq(0, NavigationPolicy.choices(overlay).size(), "Search cannot cause query submission");
        eq(NavigationPolicy.Destination.UNKNOWN, NavigationPolicy.observe("tiktok", overlayXml.replace("class='android.widget.EditText'", "class='android.widget.TextView'")).destination(), "real editable field required");
        eq(NavigationPolicy.Destination.UNKNOWN, NavigationPolicy.observe("tiktok", overlayXml.replace("id/bs5", "id/unknown")).destination(), "Search back control required");
        eq(NavigationPolicy.Destination.UNKNOWN, NavigationPolicy.observe("tiktok", overlayXml.replace("</hierarchy>", ttSearchForm() + "</hierarchy>")).destination(), "duplicate Search form rejected");
        eq("MANUAL_SIGN_IN_REQUIRED", NavigationPolicy.observe("tiktok", overlayXml.replace("</hierarchy>", "<node text='Log in to TikTok'/></hierarchy>")).blocker(), "Search overlay cannot bypass sign-in");
        eq(NavigationPolicy.Destination.HOME, NavigationPolicy.observe("tiktok", ttBothXml.replace("</hierarchy>", "<node displayed='false'>" + ttSearchForm() + "</node></hierarchy>")).destination(), "hidden Search does not override Home");
        var reportDirectory = java.nio.file.Files.createTempDirectory("navigation-report-test-");
        try {
            var report = new NavigationRunReport("tiktok", "emulator-fixture", "search", reportDirectory);
            report.action("LLM-selected goal", ttSearchAction);
            report.verified("start", ttBoth, "before.png");
            report.write(false, "SAME_SCREEN", 1, List.of("Goal: SEARCH"));
            Map<?, ?> result = new org.openqa.selenium.json.Json().toType(java.nio.file.Files.readString(reportDirectory.resolve("result.json")), Map.class);
            eq(false, result.get("passed"), "report preserves failure despite a tap");
            eq("home-to-search", result.get("goal"), "report preserves explicit goal");
            eq("SAME_SCREEN", result.get("reason"), "report has accurate stop reason");
            eq(1, ((List<?>) result.get("actions")).size(), "report includes actual actions");
            eq(1, ((List<?>) result.get("verifiedScreens")).size(), "failed destination not fabricated");
            eq(0, ((List<?>) result.get("screenshots")).size(), "missing screenshots not claimed");
            eq(true, java.nio.file.Files.readString(reportDirectory.resolve("report.md")).contains("Screenshot unavailable"), "human report flags missing screenshot");
            java.nio.file.Files.write(reportDirectory.resolve("after.png"), new byte[]{0});
            report.verified("destination", overlay, "after.png");
            report.write(true, "VERIFIED_DESTINATION", 1, List.of("VERIFIED"));
            result = new org.openqa.selenium.json.Json().toType(java.nio.file.Files.readString(reportDirectory.resolve("result.json")), Map.class);
            eq(true, result.get("passed"), "successful structured report");
            eq(List.of("after.png"), result.get("screenshots"), "report links existing artifacts");
            eq(true, java.nio.file.Files.readString(reportDirectory.resolve("report.md")).contains("[Screenshot](after.png)"), "human report links destination screenshot");
        } finally {
            for (String name : List.of("after.png", "result.json", "report.md")) java.nio.file.Files.deleteIfExists(reportDirectory.resolve(name));
            java.nio.file.Files.deleteIfExists(reportDirectory);
        }
        eq("", NavigationPolicy.description(null), "missing description normalization");
        eq("", NavigationPolicy.description("null"), "Appium literal null description normalization");
        eq("Profile", NavigationPolicy.description("Profile"), "real observed labels preserved");
        var igRoute = new NavigationRoute("instagram", "search-to-profile");
        eq("HOME -> SEARCH -> PROFILE", igRoute.description(), "exact Instagram route");
        eq(false, igRoute.complete(), "route not complete at start");
        rejects("MODEL_GOAL_MISMATCH", () -> igRoute.advance(home, requested, profile));
        eq(0, igRoute.completed(), "skipping Search cannot advance");
        rejects("SAME_SCREEN", () -> igRoute.advance(home, searchRequest, home));
        eq(0, igRoute.completed(), "ignored tap cannot advance");
        igRoute.advance(home, searchRequest, search);
        eq(false, igRoute.complete(), "intermediate Search cannot pass full route");
        rejects("ROUTE_SOURCE_CHANGED", () -> igRoute.advance(home, requested, profile));
        var profileFromSearch = NavigationPolicy.routeChoices(search).stream()
                .filter(c -> c.destination() == NavigationPolicy.Destination.PROFILE).findFirst().orElseThrow();
        eq("MANUAL_SIGN_IN_REQUIRED", NavigationPolicy.routeTransition(search, profileFromSearch, blocked), "login between route steps blocks transition");
        rejects("MANUAL_SIGN_IN_REQUIRED", () -> igRoute.advance(search, profileFromSearch, blocked));
        eq(1, igRoute.completed(), "blocked intermediate cannot advance");
        igRoute.advance(search, profileFromSearch, profile);
        eq(true, igRoute.complete(), "every Instagram step verified");
        rejects("ROUTE_ALREADY_COMPLETE", () -> igRoute.advance(search, profileFromSearch, profile));
        var ttRoute = new NavigationRoute("tiktok", "search-to-profile");
        eq("HOME -> SEARCH -> HOME -> PROFILE", ttRoute.description(), "exact TikTok route");
        ttRoute.advance(ttBoth, ttSearchAction, overlay);
        var returnHome = NavigationPolicy.routeChoices(overlay).get(0);
        eq(NavigationPolicy.Destination.HOME, returnHome.destination(), "only observed Search return offered to LLM");
        rejects("MODEL_GOAL_MISMATCH", () -> ttRoute.advance(overlay, ttRequested, ttProfile));
        eq("REPEATED_OR_UNOBSERVED_CONTROL", NavigationPolicy.routeTransition(overlay, ttRequested, ttProfile), "overlay prevents tapping background Profile");
        ttRoute.advance(overlay, returnHome, ttBoth);
        eq(false, ttRoute.complete(), "returning Home alone cannot complete route");
        ttRoute.advance(ttBoth, ttRequested, ttProfile);
        eq(true, ttRoute.complete(), "TikTok all three steps verified");
        var unknown = observe("<hierarchy/>");
        eq(0, NavigationPolicy.routeChoices(unknown).size(), "unknown route source offers no controls");
        eq("UNKNOWN_SOURCE_SCREEN", NavigationPolicy.routeTransition(unknown, requested, profile), "unknown route source rejected");
        eq("DESTINATION_NOT_VERIFIED", NavigationPolicy.routeTransition(search, profileFromSearch, unknown), "unknown intermediate never verified");
        var ambiguousSearch = observe(searchXml.replace("</hierarchy>", node("tab_bar", "", tab("profile_tab", "Profile", false, false)) + "</hierarchy>"));
        eq(false, NavigationPolicy.routeChoices(ambiguousSearch).contains(profileFromSearch), "ambiguous intermediate control rejected");
        eq("ONBOARDING_NAVIGATION_REQUIRED", NavigationPolicy.observe("tiktok", ttScreen(true).replace("</hierarchy>", ttNode("bxo", "text='Your avatar, your style'", "") + "</hierarchy>")).blocker(), "observed avatar overlay blocks route");
        var routeDirectory = java.nio.file.Files.createTempDirectory("route-report-test-");
        try {
            var report = new NavigationRunReport("instagram", "fixture", "search-to-profile", routeDirectory);
            report.route(igRoute.screens());
            for (String file : List.of("step-1-before.png", "step-1-after.png", "step-2-before.png", "step-2-after.png"))
                java.nio.file.Files.write(routeDirectory.resolve(file), new byte[]{0});
            report.action("preparation", homeControl);
            report.beginStep(1, home.destination(), searchRequest, "step-1-before.png");
            report.finishStep(1, search.destination(), "step-1-after.png", "VERIFIED");
            try { report.write(true, "VERIFIED_ROUTE", 1, List.of()); throw new AssertionError("partial route PASS accepted"); }
            catch (IllegalStateException e) { eq("REPORT_INCOMPLETE_ROUTE", e.getMessage(), "report rejects partial route PASS"); }
            report.beginStep(2, search.destination(), profileFromSearch, "step-2-before.png");
            report.failStep(2, "VERIFICATION_REQUIRED");
            report.write(false, "VERIFICATION_REQUIRED", 2, List.of());
            Map<?, ?> result = new org.openqa.selenium.json.Json().toType(java.nio.file.Files.readString(routeDirectory.resolve("result.json")), Map.class);
            eq(false, result.get("passed"), "failed second step remains FAIL");
            eq("PROFILE", result.get("requestedDestination"), "route final goal recorded separately");
            var steps = (List<?>) result.get("steps");
            eq("VERIFIED", ((Map<?, ?>)steps.get(0)).get("result"), "first verified step retained on later failure");
            eq("VERIFICATION_REQUIRED", ((Map<?, ?>)steps.get(1)).get("result"), "failed step reason recorded");
            eq("", ((Map<?, ?>)steps.get(1)).get("verifiedDestination"), "failed screen not invented");
            eq(2, steps.size(), "preparation excluded from route trace");
            report.finishStep(2, profile.destination(), "step-2-after.png", "VERIFIED");
            report.write(true, "VERIFIED_ROUTE", 2, List.of());
            eq(true, java.nio.file.Files.readString(routeDirectory.resolve("report.md")).contains("[Screenshot](step-2-after.png)"), "per-step screenshot in human trace");
            java.nio.file.Files.delete(routeDirectory.resolve("step-2-after.png"));
            try { report.write(true, "VERIFIED_ROUTE", 2, List.of()); throw new AssertionError("missing screenshot PASS accepted"); }
            catch (IllegalStateException e) { eq("REPORT_UNVERIFIED_ROUTE_STEP", e.getMessage(), "missing evidence prevents route PASS"); }
        } finally {
            try (var files = java.nio.file.Files.list(routeDirectory)) {
                for (var file : files.toList()) java.nio.file.Files.delete(file);
            }
            java.nio.file.Files.delete(routeDirectory);
        }
        var stable = new NavigationPolicy.Stability();
        eq(false, stable.accept("VERIFIED", 0), "one observation insufficient");
        eq(false, stable.accept("VERIFIED", 1000), "two observations insufficient");
        eq(true, stable.accept("VERIFIED", 2000), "three observations over two seconds");
        eq(false, stable.accept("SAME_SCREEN", 2500), "regression resets stability");
        eq(false, stable.accept("VERIFIED", 3000), "old stable result cannot leak into new sample");
        eq(false, stable.accept("VERIFIED", 3100), "rapid observations insufficient");
        eq(false, stable.accept("VERIFIED", 3200), "three rapid observations still insufficient");
        eq(true, stable.accept("VERIFIED", 5000), "renewed sustained destination");
        System.out.println("VERIFIED_NAVIGATION_TESTS_PASSED: " + assertions + " assertions");
    }
}
