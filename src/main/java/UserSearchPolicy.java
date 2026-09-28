import org.openqa.selenium.json.Json;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.file.*;
import java.util.*;

/** Exact account identity checks. No fuzzy names, result ranking, or video fallbacks. */
public final class UserSearchPolicy {
    public record Contract(String app, String queryId, String tabId, String tabContainerId,
                           String resultsId, String rowId, String usernameId,
                           String profileHandleId, List<String> profileMarkers) {
        public Contract {
            String prefix = pkg(app) + ":id/";
            for (String id : List.of(queryId, tabContainerId, resultsId, rowId, usernameId, profileHandleId))
                if (!id.startsWith(prefix) || !id.substring(prefix.length()).matches("[A-Za-z0-9_]+"))
                    throw new IllegalStateException("INVALID_SEARCH_UI_CONTRACT");
            if (!tabId.isEmpty() &&
                    (!tabId.startsWith(prefix) || !tabId.substring(prefix.length()).matches("[A-Za-z0-9_]+")))
                throw new IllegalStateException("INVALID_SEARCH_UI_CONTRACT");
            profileMarkers = List.copyOf(profileMarkers);
            if (profileMarkers.isEmpty() || profileMarkers.stream().anyMatch(id -> !id.startsWith(prefix)
                    || !id.substring(prefix.length()).matches("[A-Za-z0-9_]+")))
                throw new IllegalStateException("INVALID_PROFILE_MARKERS");
            if (queryId.equals(profileHandleId) || resultsId.equals(rowId) || rowId.equals(usernameId))
                throw new IllegalStateException("AMBIGUOUS_SEARCH_UI_CONTRACT");
        }
        public String category() { return app.equals("instagram") ? "Accounts" : "Users"; }
    }
    public record Target(String xpath, String handle) {}

    public static String categoryXpath(Contract c) {
        if (c.tabId().isEmpty()) return "//*[@resource-id='" + c.tabContainerId()
                + "']//*[@class='" + tabClass(c) + "' and (@content-desc='" + c.category()
                + "' or .//*[@text='" + c.category() + "'])]";
        return "//*[@resource-id='" + c.tabContainerId() + "']//*[@resource-id='" + c.tabId()
                + "' and (@text='" + c.category() + "' or @content-desc='" + c.category()
                + "' or .//*[@text='" + c.category() + "'])]";
    }
    private static String tabClass(Contract c) {
        return c.app().equals("instagram") ? "android.widget.Button" : "android.widget.FrameLayout";
    }

    public static String pkg(String app) {
        return switch (app) {
            case "instagram" -> "com.instagram.android";
            case "tiktok" -> "com.zhiliaoapp.musically";
            default -> throw new IllegalStateException("UNSUPPORTED_APP");
        };
    }
    public static String handle(String app, String raw) {
        pkg(app);
        if (raw == null || raw.isBlank()) throw new IllegalStateException("HANDLE_REQUIRED: supply an exact account handle");
        String value = raw.startsWith("@") ? raw.substring(1) : raw;
        int limit = app.equals("instagram") ? 30 : 24;
        if (!value.matches("[A-Za-z0-9._]{1," + limit + "}") || value.endsWith(".")
                || value.startsWith(".") || value.contains(".."))
            throw new IllegalStateException("INVALID_HANDLE: use an exact username, not a URL, display name, or query");
        return value;
    }
    public static boolean exact(String target, String observed) {
        if (observed == null) return false;
        // TikTok's observed username field wraps the entire ASCII username in LRM + FSI/PDI.
        // Accept only this complete wrapper; embedded controls and lookalike letters remain invalid.
        if (observed.startsWith("\u200e\u2068") && observed.endsWith("\u2069"))
            observed = observed.substring(2, observed.length() - 1);
        String value = observed.startsWith("@") ? observed.substring(1) : observed;
        return value.matches("[A-Za-z0-9._]+") && target.equalsIgnoreCase(value); // ASCII case only; no Unicode confusables.
    }
    public static Contract load(String app, Path file) throws Exception {
        if (!Files.isRegularFile(file)) throw new IllegalStateException("SEARCH_UI_CONTRACT_REQUIRED: inspect account result controls before enabling profile opening");
        Map<?, ?> data = new Json().toType(Files.readString(file), Map.class);
        if (!app.equals(data.get("app"))) throw new IllegalStateException("SEARCH_UI_CONTRACT_APP_MISMATCH");
        var markers = data.get("profileMarkers");
        if (!(markers instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String)))
            throw new IllegalStateException("INVALID_PROFILE_MARKERS");
        return new Contract(app, string(data, "queryId"), string(data, "tabId"), string(data, "tabContainerId"),
                string(data, "resultsId"), string(data, "rowId"), string(data, "usernameId"),
                string(data, "profileHandleId"), list.stream().map(String.class::cast).toList());
    }
    private static String string(Map<?, ?> data, String key) {
        if (!(data.get(key) instanceof String value)) throw new IllegalStateException("SEARCH_UI_CONTRACT_MISSING_" + key);
        return value;
    }

    public static final class Ui {
        private final String app;
        private final List<Element> nodes;
        public Ui(String app, String xml) throws Exception {
            this.app = app;
            String blocker = NavigationPolicy.observe(app, xml).blocker();
            if (!blocker.isEmpty()) throw new IllegalStateException(blocker);
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            NodeList all = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getElementsByTagName("*");
            nodes = new ArrayList<>();
            for (int i = 0; i < all.getLength(); i++) {
                Element e = (Element) all.item(i);
                if (visible(e) && e.getAttribute("package").equals(pkg(app))) nodes.add(e);
            }
        }
        public List<Element> byId(String id) { return nodes.stream().filter(e -> id.equals(e.getAttribute("resource-id"))).toList(); }
        public Element one(String id) {
            var found = byId(id);
            if (found.isEmpty()) throw new IllegalStateException("MISSING_CONTROL: " + id);
            if (found.size() != 1) throw new IllegalStateException("AMBIGUOUS_CONTROL: " + id);
            return found.get(0);
        }
        public Element input(String id) {
            var e = one(id);
            if (!e.getAttribute("class").equals("android.widget.EditText") || !enabled(e) || e.getAttribute("password").equals("true"))
                throw new IllegalStateException("SEARCH_INPUT_NOT_VERIFIED");
            return e;
        }
        public void query(String id, String handle) {
            if (!handle.equals(input(id).getAttribute("text"))) throw new IllegalStateException("QUERY_CHANGED");
        }
        public void resultQuery(String id, String handle) {
            var e = one(id);
            if (!enabled(e) || !Set.of("android.widget.EditText", "android.widget.TextView").contains(e.getAttribute("class")))
                throw new IllegalStateException("RESULT_QUERY_NOT_VERIFIED");
            if (!handle.equals(e.getAttribute("text"))) throw new IllegalStateException("QUERY_CHANGED");
        }
        public Element category(Contract c) {
            var container = one(c.tabContainerId());
            var candidates = c.tabId().isEmpty() ? nodes.stream().filter(e ->
                    e.getAttribute("class").equals(tabClass(c))).toList() : byId(c.tabId());
            var matches = candidates.stream().filter(e -> inside(e, container) && categoryLabel(e, c.category())).toList();
            if (matches.isEmpty()) throw new IllegalStateException("MISSING_ACCOUNT_CATEGORY");
            if (matches.size() != 1) throw new IllegalStateException("AMBIGUOUS_ACCOUNT_CATEGORY");
            var tab = matches.get(0);
            if (!enabled(tab) || (!selected(tab) && !tab.getAttribute("clickable").equals("true")))
                throw new IllegalStateException("ACCOUNT_CATEGORY_NOT_VERIFIED");
            return tab;
        }
        private boolean categoryLabel(Element tab, String label) {
            return label.equals(tab.getAttribute("text")) || label.equals(tab.getAttribute("content-desc"))
                    || nodes.stream().anyMatch(e -> inside(e, tab) && label.equals(e.getAttribute("text")));
        }
        private boolean selected(Element tab) {
            return tab.getAttribute("selected").equals("true") || nodes.stream()
                    .anyMatch(e -> inside(e, tab) && e.getAttribute("selected").equals("true"));
        }
        public void accounts(Contract c, String handle) {
            resultQuery(c.queryId(), handle);
            Element tab = category(c);
            if (!selected(tab)) throw new IllegalStateException("ACCOUNT_CATEGORY_NOT_SELECTED");
            var container = one(c.tabContainerId());
            if (nodes.stream().anyMatch(e -> e != tab && inside(e, container) && !inside(e, tab) && !inside(tab, e)
                    && (e.getAttribute("clickable").equals("true") || categoryLabel(e, "Top") || categoryLabel(e, "Videos")) && selected(e)))
                throw new IllegalStateException("AMBIGUOUS_RESULT_CATEGORIES");
            one(c.resultsId());
        }
        public Target exactAccount(Contract c, String handle) {
            accounts(c, handle);
            Element results = one(c.resultsId());
            var matches = new ArrayList<Element>();
            for (Element username : byId(c.usernameId())) {
                if (!inside(username, results) || !exact(handle, username.getAttribute("text"))) continue;
                if (!enabled(username)) throw new IllegalStateException("ACCOUNT_ROW_NOT_VERIFIED");
                Element row = ancestor(username, c.rowId());
                if (row == null || !inside(row, results) || !enabled(row) || !row.getAttribute("clickable").equals("true")
                        || (row.getAttribute("class").endsWith("Button") && !(app.equals("instagram") &&
                        c.rowId().equals("com.instagram.android:id/row_search_user_container")))) throw new IllegalStateException("ACCOUNT_ROW_NOT_VERIFIED");
                if (byId(c.usernameId()).stream().filter(e -> inside(e, row)).count() != 1)
                    throw new IllegalStateException("AMBIGUOUS_ACCOUNT_ROW");
                matches.add(row);
            }
            if (matches.isEmpty()) throw new IllegalStateException("EXACT_ACCOUNT_NOT_FOUND");
            if (matches.size() != 1) throw new IllegalStateException("AMBIGUOUS_EXACT_ACCOUNT");
            Element row = matches.get(0);
            Element username = byId(c.usernameId()).stream().filter(e -> inside(e, row)).findFirst().orElseThrow();
            String actual = username.getAttribute("text");
            // Actual is an exact ASCII handle (optionally @); no query text is interpolated unchecked.
            String xpath = "//*[@resource-id='" + c.resultsId() + "']//*[@resource-id='" + c.rowId()
                    + "' and .//*[@resource-id='" + c.usernameId() + "' and @text='" + actual + "']]";
            return new Target(xpath, actual);
        }
        public String profile(Contract c, String handle) {
            if (!byId(c.resultsId()).isEmpty() || !byId(c.tabContainerId()).isEmpty() || !byId(c.queryId()).isEmpty())
                throw new IllegalStateException("RESULTS_STILL_VISIBLE");
            for (String marker : c.profileMarkers()) one(marker);
            String actual = one(c.profileHandleId()).getAttribute("text");
            if (!exact(handle, actual)) throw new IllegalStateException("PROFILE_HANDLE_MISMATCH");
            return actual;
        }
    }
    public static boolean enabled(Element e) { return e.getAttribute("enabled").equals("true"); }
    private static boolean visible(Element e) {
        for (Node p = e; p instanceof Element x; p = p.getParentNode())
            if (x.getAttribute("displayed").equals("false")) return false;
        return true;
    }
    private static boolean inside(Element e, Element ancestor) {
        for (Node p = e.getParentNode(); p != null; p = p.getParentNode()) if (p == ancestor) return true;
        return false;
    }
    private static Element ancestor(Element e, String id) {
        for (Node p = e.getParentNode(); p instanceof Element x; p = p.getParentNode())
            if (x.getAttribute("resource-id").equals(id)) return x;
        return null;
    }
}
