import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Pattern;

/** Pure, fail-closed interpretation of observed native accessibility trees. */
public final class NavigationPolicy {
    public enum Destination { HOME, PROFILE, SEARCH, UNKNOWN }
    public record Control(String label, String id, Destination destination, boolean selected) {}
    public record Screen(Destination destination, Set<String> markers, List<Control> controls, String blocker) {
        public String summary() { return destination + "; markers=" + markers; }
    }
    private static final String IG = "com.instagram.android:id/";
    // Observed on TikTok 47.0.3. Fail closed when the native layout changes.
    private static final String TT = "com.zhiliaoapp.musically:id/";
    private static final Map<String, Destination> TIKTOK_TABS = Map.of(
            TT + "omq", Destination.HOME, TT + "oms", Destination.PROFILE, TT + "k_8", Destination.SEARCH);
    private static final Set<String> TIKTOK_MARKERS = Set.of(TT + "long_press_layout", TT + "t5q");
    private static final Map<String, Destination> TABS = Map.of(
            IG + "feed_tab", Destination.HOME, IG + "profile_tab", Destination.PROFILE,
            IG + "search_tab", Destination.SEARCH);
    private static final Map<Destination, String> LABELS = Map.of(
            Destination.HOME, "Home", Destination.PROFILE, "Profile", Destination.SEARCH, "Search and explore");
    private static final Set<String> MARKERS = Set.of(IG + "reels_tray_container",
            IG + "profile_header_container", IG + "row_profile_header", IG + "explore_action_bar");
    private static final Set<String> LOGIN = Set.of("join instagram", "i already have a profile",
            "log in to instagram", "log in to tiktok", "sign up for tiktok", "log in or sign up",
            "log in", "login", "enter your password", "create account");
    private static final Pattern VERIFICATION = Pattern.compile(
            "(?i).*(captcha|verify to continue|verify you're human|security check|suspicious activity).*" );

    private NavigationPolicy() {}

    public static Screen observe(String app, String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        NodeList nodes = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)))
                .getElementsByTagName("*");
        boolean tiktok = app.equals("tiktok");
        String expectedPackage = tiktok ? "com.zhiliaoapp.musically" : "com.instagram.android";
        Map<String, Destination> tabs = tiktok ? TIKTOK_TABS : TABS;
        String tabBar = tiktok ? TT + "omy" : IG + "tab_bar";
        Set<String> markers = new TreeSet<>();
        List<Control> controls = new ArrayList<>();
        String blocker = "";
        int searchInputs = 0, searchButtons = 0, searchBacks = 0;
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (!visible(node)) continue;
            if (node.getAttribute("resource-id").equals("com.android.permissioncontroller:id/permission_message"))
                blocker = "SYSTEM_PERMISSION_PROMPT";
            for (String attribute : List.of("text", "content-desc")) {
                String value = node.getAttribute(attribute).strip().toLowerCase(Locale.ROOT);
                if (value.contains("keeps stopping") || value.contains("isn't responding"))
                    blocker = "APP_CRASH_OR_ANR_PROMPT";
                else if (!blocker.equals("APP_CRASH_OR_ANR_PROMPT") && (value.equals("when\u2019s your birthdate?") || value.equals("when's your birthdate?")))
                    blocker = "MANUAL_AGE_VERIFICATION_REQUIRED";
                else if (!blocker.equals("APP_CRASH_OR_ANR_PROMPT") && VERIFICATION.matcher(value).matches())
                    blocker = "VERIFICATION_REQUIRED";
                else if (blocker.isEmpty() && LOGIN.contains(value)) blocker = "MANUAL_SIGN_IN_REQUIRED";
            }
            if (blocker.isEmpty() && "true".equals(node.getAttribute("password")))
                blocker = "MANUAL_SIGN_IN_REQUIRED";
            if (!Set.of("instagram", "tiktok").contains(app) || !node.getAttribute("package").equals(expectedPackage)) continue;
            String id = node.getAttribute("resource-id");
            if ((tiktok ? TIKTOK_MARKERS : MARKERS).contains(id)) markers.add(id);
            if (tiktok) {
                if (insideContainer(node, TT + "vd5") && "true".equals(node.getAttribute("enabled"))) {
                    if (id.equals(TT + "hu0") && node.getAttribute("class").equals("android.widget.EditText")) searchInputs++;
                    if (id.equals(TT + "tv_search_textview") && node.getAttribute("text").equals("Search")
                            && node.getAttribute("class").equals("android.widget.Button")) searchButtons++;
                    if (id.equals(TT + "bs5") && "true".equals(node.getAttribute("clickable"))
                            && node.getAttribute("content-desc").isBlank()) searchBacks++;
                }
                if (id.equals(TT + "tv_strengthen_swipe_up_guide") && blocker.isEmpty())
                    blocker = "ONBOARDING_NAVIGATION_REQUIRED";
                if (id.equals(TT + "bxo") && node.getAttribute("text").equals("Your avatar, your style") && blocker.isEmpty())
                    blocker = "ONBOARDING_NAVIGATION_REQUIRED";
                String text = node.getAttribute("text").strip();
                String description = node.getAttribute("content-desc").strip();
                if ((text.equals("For You") || description.equals("For You")) && selected(node))
                    markers.add("tiktok:for-you");
                if (id.equals(TT + "t3y") && text.startsWith("@") && text.length() > 1)
                    markers.add("tiktok:account-handle");
                if (id.equals(TT + "t89") && insideContainer(node, TT + "t5q")
                        && Set.of("Following", "Followers", "Likes").contains(text))
                    markers.add("tiktok:metric:" + text);
            }
            Destination destination = tabs.get(id);
            if (destination == null || !"true".equals(node.getAttribute("clickable"))
                    || !"true".equals(node.getAttribute("enabled"))) continue;
            String label = node.getAttribute("content-desc").strip();
            String expectedLabel = tiktok && destination == Destination.SEARCH ? "Search" : LABELS.get(destination);
            String container = tiktok && destination == Destination.SEARCH ? TT + "uvy" : tabBar;
            if (!expectedLabel.equals(label) || !insideContainer(node, container)) continue;
            controls.add(new Control(label, id, destination, selected(node)));
        }
        // This form overlays Home; ignore its background tabs rather than misclassify it as Home.
        if (tiktok && (searchInputs > 0 || searchButtons > 0 || searchBacks > 0)) {
            boolean complete = searchInputs == 1 && searchButtons == 1 && searchBacks == 1;
            if (complete) {
                markers.clear();
                markers.addAll(Set.of("tiktok:search-form", TT + "vd5", TT + "hu0", TT + "tv_search_textview", TT + "bs5"));
                return new Screen(Destination.SEARCH, Set.copyOf(markers),
                        List.of(new Control("", TT + "bs5", Destination.HOME, false)), blocker);
            }
            return new Screen(Destination.UNKNOWN, Set.copyOf(markers), List.of(), blocker);
        }
        Set<Destination> selected = new HashSet<>();
        controls.stream().filter(Control::selected).forEach(c -> selected.add(c.destination()));
        Destination destination = selected.size() == 1 ? selected.iterator().next() : Destination.UNKNOWN;
        if (!hasDestinationMarkers(app, destination, markers)) destination = Destination.UNKNOWN;
        return new Screen(destination, Set.copyOf(markers), List.copyOf(controls), blocker);
    }

    private static boolean visible(Element node) {
        for (org.w3c.dom.Node current = node; current instanceof Element element; current = current.getParentNode())
            if ("false".equals(element.getAttribute("displayed"))) return false;
        return true;
    }

    private static boolean insideContainer(Element node, String containerId) {
        for (org.w3c.dom.Node parent = node.getParentNode(); parent instanceof Element element; parent = parent.getParentNode())
            if (containerId.equals(element.getAttribute("resource-id"))) return true;
        return false;
    }

    private static boolean selected(Element node) {
        if ("true".equals(node.getAttribute("selected"))) return true;
        NodeList children = node.getElementsByTagName("*");
        for (int i = 0; i < children.getLength(); i++) {
            Element child = (Element) children.item(i);
            if (visible(child) && "true".equals(child.getAttribute("selected"))) return true;
        }
        return false;
    }

    private static boolean hasDestinationMarkers(String app, Destination destination, Set<String> markers) {
        if (app.equals("tiktok")) return switch (destination) {
            case HOME -> markers.containsAll(Set.of(TT + "long_press_layout", "tiktok:for-you"));
            case PROFILE -> markers.containsAll(Set.of(TT + "t5q", "tiktok:account-handle",
                    "tiktok:metric:Following", "tiktok:metric:Followers", "tiktok:metric:Likes"));
            case SEARCH, UNKNOWN -> false;
        };
        return switch (destination) {
            case HOME -> markers.contains(IG + "reels_tray_container");
            case PROFILE -> markers.containsAll(Set.of(IG + "profile_header_container", IG + "row_profile_header"));
            case SEARCH -> markers.contains(IG + "explore_action_bar");
            case UNKNOWN -> false;
        };
    }

    public static String description(String value) {
        // UiAutomator2 may serialize a missing content description as the literal string "null".
        return value == null || value.equals("null") ? "" : value.strip();
    }

    public static Destination goal(String app, String name) {
        if (!Set.of("instagram", "tiktok").contains(app) || !Set.of("search", "profile").contains(name))
            throw new IllegalStateException("UNSUPPORTED_GOAL: " + app + "/" + name);
        return name.equals("search") ? Destination.SEARCH : Destination.PROFILE;
    }

    public static void requireGoal(Destination goal, Control action) {
        if (action.destination() != goal) throw new IllegalStateException("MODEL_GOAL_MISMATCH: requested " + goal + ", chose " + action.destination());
    }

    public static List<Control> choices(Screen screen) {
        if (!screen.blocker().isEmpty() || screen.destination() != Destination.HOME) return List.of();
        return routeChoices(screen);
    }

    public static List<Control> routeChoices(Screen screen) {
        if (!screen.blocker().isEmpty() || screen.destination() == Destination.UNKNOWN) return List.of();
        return screen.controls().stream().filter(c -> !c.selected() && c.destination() != screen.destination())
                .filter(c -> screen.controls().stream().filter(other -> other.id().equals(c.id())).count() == 1)
                .filter(c -> screen.controls().stream().filter(other -> other.destination() == c.destination()).count() == 1)
                .toList();
    }

    public static String transition(Screen before, Control requested, Screen after) {
        if (!after.blocker().isEmpty()) return after.blocker();
        if (!before.blocker().isEmpty() || before.destination() != Destination.HOME)
            return "START_SCREEN_NOT_VERIFIED_HOME";
        return routeTransition(before, requested, after);
    }

    public static String routeTransition(Screen before, Control requested, Screen after) {
        if (!after.blocker().isEmpty()) return after.blocker();
        if (!before.blocker().isEmpty()) return before.blocker();
        if (before.destination() == Destination.UNKNOWN) return "UNKNOWN_SOURCE_SCREEN";
        if (!routeChoices(before).contains(requested)) return "REPEATED_OR_UNOBSERVED_CONTROL";
        if (after.destination() == before.destination()) return "SAME_SCREEN";
        if (after.destination() == Destination.UNKNOWN) return "DESTINATION_NOT_VERIFIED";
        if (after.destination() != requested.destination()) return "WRONG_DESTINATION";
        boolean searchForm = requested.id().equals(TT + "k_8") && after.destination() == Destination.SEARCH
                && after.markers().contains("tiktok:search-form");
        boolean searchReturn = before.destination() == Destination.SEARCH && before.markers().contains("tiktok:search-form")
                && requested.id().equals(TT + "bs5") && after.destination() == Destination.HOME
                && after.controls().stream().filter(c -> c.id().equals(TT + "omq") && c.selected()).count() == 1;
        if (!searchForm && !searchReturn && after.controls().stream().filter(c -> c.id().equals(requested.id())).count() != 1)
            return "AMBIGUOUS_DESTINATION";
        if (before.markers().equals(after.markers())) return "SAME_SCREEN_CONTENT";
        return "VERIFIED";
    }

    /** Require independent observations separated by at least two seconds, not one transient tab state. */
    public static final class Stability {
        private long since = -1;
        private int count;
        public boolean accept(String verdict, long elapsedMillis) {
            if (!verdict.equals("VERIFIED")) { since = -1; count = 0; return false; }
            if (since < 0) since = elapsedMillis;
            count++;
            return count >= 3 && elapsedMillis - since >= 2000;
        }
    }
}
