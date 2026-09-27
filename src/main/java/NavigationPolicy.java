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
        Set<String> markers = new TreeSet<>();
        List<Control> controls = new ArrayList<>();
        String blocker = "";
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (!visible(node)) continue;
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
            if (!app.equals("instagram") || !node.getAttribute("package").equals("com.instagram.android")) continue;
            String id = node.getAttribute("resource-id");
            if (MARKERS.contains(id)) markers.add(id);
            Destination destination = TABS.get(id);
            if (destination == null || !"true".equals(node.getAttribute("clickable"))
                    || !"true".equals(node.getAttribute("enabled"))) continue;
            String label = node.getAttribute("content-desc").strip();
            if (!LABELS.get(destination).equals(label) || !insideTabBar(node)) continue;
            controls.add(new Control(label, id, destination, selected(node)));
        }
        Set<Destination> selected = new HashSet<>();
        controls.stream().filter(Control::selected).forEach(c -> selected.add(c.destination()));
        Destination destination = selected.size() == 1 ? selected.iterator().next() : Destination.UNKNOWN;
        if (!hasDestinationMarkers(destination, markers)) destination = Destination.UNKNOWN;
        return new Screen(destination, Set.copyOf(markers), List.copyOf(controls), blocker);
    }

    private static boolean visible(Element node) {
        for (org.w3c.dom.Node current = node; current instanceof Element element; current = current.getParentNode())
            if ("false".equals(element.getAttribute("displayed"))) return false;
        return true;
    }

    private static boolean insideTabBar(Element node) {
        for (org.w3c.dom.Node parent = node.getParentNode(); parent instanceof Element element; parent = parent.getParentNode())
            if ((IG + "tab_bar").equals(element.getAttribute("resource-id"))) return true;
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

    private static boolean hasDestinationMarkers(Destination destination, Set<String> markers) {
        return switch (destination) {
            case HOME -> markers.contains(IG + "reels_tray_container");
            case PROFILE -> markers.containsAll(Set.of(IG + "profile_header_container", IG + "row_profile_header"));
            case SEARCH -> markers.contains(IG + "explore_action_bar");
            case UNKNOWN -> false;
        };
    }

    public static List<Control> choices(Screen screen) {
        if (!screen.blocker().isEmpty() || screen.destination() != Destination.HOME) return List.of();
        return screen.controls().stream().filter(c -> !c.selected() && c.destination() != Destination.HOME)
                .filter(c -> screen.controls().stream().filter(other -> other.id().equals(c.id())).count() == 1)
                .toList();
    }

    public static String transition(Screen before, Control requested, Screen after) {
        if (!after.blocker().isEmpty()) return after.blocker();
        if (!before.blocker().isEmpty() || before.destination() != Destination.HOME)
            return "START_SCREEN_NOT_VERIFIED_HOME";
        if (!choices(before).contains(requested)) return "REPEATED_OR_UNOBSERVED_CONTROL";
        if (after.destination() == before.destination()) return "SAME_SCREEN";
        if (after.destination() == Destination.UNKNOWN) return "DESTINATION_NOT_VERIFIED";
        if (after.destination() != requested.destination()) return "WRONG_DESTINATION";
        if (after.controls().stream().filter(c -> c.id().equals(requested.id())).count() != 1)
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
