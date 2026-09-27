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
