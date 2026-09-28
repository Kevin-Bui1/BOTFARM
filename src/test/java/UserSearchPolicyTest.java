import java.util.*;

/** Synthetic identity fixtures only; no fixture username is ever searched on a device. */
public final class UserSearchPolicyTest {
    private static int assertions;
    private static String pkg;
    private static String node(String id, String attrs, String body) {
        return "<node package='" + pkg + "' resource-id='" + pkg + ":id/" + id
                + "' enabled='true' displayed='true' " + attrs + ">" + body + "</node>";
    }
    private static void eq(Object expected, Object actual, String message) {
        assertions++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void rejects(String reason, Operation op) throws Exception {
        try { op.run(); } catch (IllegalStateException e) { eq(true, e.getMessage().startsWith(reason), reason); return; }
        throw new AssertionError("Expected " + reason);
    }
    public static int run() throws Exception {
        assertions = 0;
        for (String app : List.of("instagram", "tiktok")) {
            pkg = UserSearchPolicy.pkg(app);
            String p = pkg + ":id/";
            eq("Fixture_User", UserSearchPolicy.handle(app, "@Fixture_User"), "preserve supplied spelling");
            rejects("HANDLE_REQUIRED", () -> UserSearchPolicy.handle(app, null));
            rejects("HANDLE_REQUIRED", () -> UserSearchPolicy.handle(app, ""));
            for (String invalid : List.of("https://example.com/user", "fixture user", "@@fixture", "fixture*", "foo..bar", "fixture.", " fixture"))
                rejects("INVALID_HANDLE", () -> UserSearchPolicy.handle(app, invalid));
            var c = new UserSearchPolicy.Contract(app, p + "query", p + "account_tab", p + "tabs", p + "results",
                    p + "account_row", p + "username", p + "profile_handle", List.of(p + "profile_header", p + "profile_metrics"));
            String query = node("query", "class='android.widget.EditText' text='fixture_user'", "");
            String tabs = node("tabs", "", node("account_tab", "text='" + c.category() + "' clickable='true' selected='true'", ""));
            String row = node("account_row", "class='android.view.ViewGroup' clickable='true'",
                    node("display_name", "text='Different display name'", "") + node("username", "text='@fixture_user'", ""));
            String xml = "<hierarchy>" + query + tabs + node("results", "", row) + "</hierarchy>";
            var ui = new UserSearchPolicy.Ui(app, xml);
            if (app.equals("instagram")) {
                var nativeChips = new UserSearchPolicy.Contract(app, c.queryId(), "", c.tabContainerId(), c.resultsId(),
                        p + "row_search_user_container", c.usernameId(), c.profileHandleId(), c.profileMarkers());
                String chip = "<node package='" + pkg + "' class='android.widget.Button' enabled='true' clickable='true'>"
                        + node("chip_label", "text='Accounts' selected='true'", "") + "</node>";
                String observed = xml.replace(tabs, node("tabs", "", chip)).replace("class='android.widget.EditText'", "class='android.widget.TextView'")
                        .replace(p + "account_row", p + "row_search_user_container").replace("class='android.view.ViewGroup'", "class='android.widget.Button'");
                eq("@fixture_user", new UserSearchPolicy.Ui(app, observed).exactAccount(nativeChips, "fixture_user").handle(), "observed static query, account chip and native account button row");
                rejects("RESULT_QUERY_NOT_VERIFIED", () -> new UserSearchPolicy.Ui(app, observed.replace("class='android.widget.TextView'", "class='android.widget.ImageView'")).exactAccount(nativeChips, "fixture_user"));
                rejects("QUERY_CHANGED", () -> new UserSearchPolicy.Ui(app, observed.replace("text='fixture_user'", "text='different_query'")).exactAccount(nativeChips, "fixture_user"));
                rejects("AMBIGUOUS_ACCOUNT_CATEGORY", () -> new UserSearchPolicy.Ui(app, observed.replace(chip, chip + chip)).exactAccount(nativeChips, "fixture_user"));
            }
            if (app.equals("tiktok")) {
                var nativeTabs = new UserSearchPolicy.Contract(app, c.queryId(), "", c.tabContainerId(), c.resultsId(), c.rowId(), c.usernameId(), c.profileHandleId(), c.profileMarkers());
                String tab = "<node package='" + pkg + "' class='android.widget.FrameLayout' content-desc='Users' enabled='true' selected='true' clickable='false'/>";
                String observed = xml.replace(tabs, node("tabs", "", tab)).replace("@fixture_user", "\u200e\u2068fixture_user\u2069");
                eq("\u200e\u2068fixture_user\u2069", new UserSearchPolicy.Ui(app, observed).exactAccount(nativeTabs, "fixture_user").handle(), "observed id-less selected Users tab and whole username wrapper");
                rejects("AMBIGUOUS_ACCOUNT_CATEGORY", () -> new UserSearchPolicy.Ui(app, observed.replace(tab, tab + tab)).exactAccount(nativeTabs, "fixture_user"));
                rejects("ACCOUNT_CATEGORY_NOT_VERIFIED", () -> new UserSearchPolicy.Ui(app, observed.replace("selected='true'", "selected='false'")).exactAccount(nativeTabs, "fixture_user"));
            }
            var target = ui.exactAccount(c, "fixture_user");
            eq("@fixture_user", target.handle(), "exact username field");
            eq(true, target.xpath().contains("@resource-id='" + p + "username'"), "locator scoped to username field");
            rejects("ACCOUNT_CATEGORY_NOT_SELECTED", () -> new UserSearchPolicy.Ui(app, xml.replace("selected='true'", "selected='false'")).exactAccount(c, "fixture_user"));
            rejects("MISSING_ACCOUNT_CATEGORY", () -> new UserSearchPolicy.Ui(app, xml.replace("text='" + c.category() + "'", "text='Videos'")).exactAccount(c, "fixture_user"));
            String competingTabs = node("tabs", "", node("account_tab", "text='" + c.category() + "' clickable='true' selected='true'", "")
                    + node("account_tab", "text='Videos' clickable='true' selected='true'", ""));
            rejects("AMBIGUOUS_RESULT_CATEGORIES", () -> new UserSearchPolicy.Ui(app, xml.replace(tabs, competingTabs)).exactAccount(c, "fixture_user"));
            eq("@fixture_user", new UserSearchPolicy.Ui(app, xml.replace(tabs, competingTabs.replace("text='Videos' clickable='true' selected='true'", "text='Videos' clickable='true' selected='false'"))).exactAccount(c, "fixture_user").handle(), "shared tab resource IDs require exact category label");
            rejects("QUERY_CHANGED", () -> ui.exactAccount(c, "other_user"));
            rejects("EXACT_ACCOUNT_NOT_FOUND", () -> new UserSearchPolicy.Ui(app, xml.replace("text='@fixture_user'", "text='@fixture_user2'")).exactAccount(c, "fixture_user"));
            rejects("EXACT_ACCOUNT_NOT_FOUND", () -> new UserSearchPolicy.Ui(app, xml.replace("text='@fixture_user'", "text='@other_user'").replace("Different display name", "fixture_user")).exactAccount(c, "fixture_user"));
            String duplicated = "<hierarchy>" + query + tabs + node("results", "", row + row) + "</hierarchy>";
            rejects("AMBIGUOUS_EXACT_ACCOUNT", () -> new UserSearchPolicy.Ui(app, duplicated).exactAccount(c, "fixture_user"));
            rejects("ACCOUNT_ROW_NOT_VERIFIED", () -> new UserSearchPolicy.Ui(app, xml.replace("class='android.view.ViewGroup'", "class='android.widget.Button'")).exactAccount(c, "fixture_user"));
            rejects("EXACT_ACCOUNT_NOT_FOUND", () -> new UserSearchPolicy.Ui(app, xml.replace(p + "username", p + "video_caption")).exactAccount(c, "fixture_user"));
            String hidden = "<hierarchy>" + query + tabs + node("results", "", "<node displayed='false'>" + row + "</node>") + "</hierarchy>";
            rejects("EXACT_ACCOUNT_NOT_FOUND", () -> new UserSearchPolicy.Ui(app, hidden).exactAccount(c, "fixture_user"));
            String profile = "<hierarchy>" + node("profile_handle", "text='@Fixture_User'", "") + node("profile_header", "", "") + node("profile_metrics", "", "") + "</hierarchy>";
            eq("@Fixture_User", new UserSearchPolicy.Ui(app, profile).profile(c, "fixture_user"), "independent final profile handle");
            rejects("PROFILE_HANDLE_MISMATCH", () -> new UserSearchPolicy.Ui(app, profile.replace("@Fixture_User", "@different")).profile(c, "fixture_user"));
            rejects("MISSING_CONTROL", () -> new UserSearchPolicy.Ui(app, profile.replace(p + "profile_metrics", p + "other")).profile(c, "fixture_user"));
            rejects("AMBIGUOUS_CONTROL", () -> new UserSearchPolicy.Ui(app, profile.replace("</hierarchy>", node("profile_handle", "text='@Fixture_User'", "") + "</hierarchy>")).profile(c, "fixture_user"));
            rejects("RESULTS_STILL_VISIBLE", () -> new UserSearchPolicy.Ui(app, profile.replace("</hierarchy>", query + "</hierarchy>")).profile(c, "fixture_user"));
            rejects("MANUAL_SIGN_IN_REQUIRED", () -> new UserSearchPolicy.Ui(app, xml.replace("</hierarchy>", "<node text='Log in'/></hierarchy>")));
            rejects("VERIFICATION_REQUIRED", () -> new UserSearchPolicy.Ui(app, profile.replace("</hierarchy>", "<node text='Security check'/></hierarchy>")));
            var tmp = java.nio.file.Files.createTempDirectory("search-contract-test-");
            try {
                rejects("SEARCH_UI_CONTRACT_REQUIRED", () -> UserSearchPolicy.load(app, tmp.resolve("missing.json")));
                var data = new LinkedHashMap<String, Object>();
                data.put("app", app); data.put("queryId", c.queryId()); data.put("tabId", c.tabId()); data.put("tabContainerId", c.tabContainerId());
                data.put("resultsId", c.resultsId()); data.put("rowId", c.rowId()); data.put("usernameId", c.usernameId());
                data.put("profileHandleId", c.profileHandleId()); data.put("profileMarkers", c.profileMarkers());
                var file = tmp.resolve("contract.json");
                java.nio.file.Files.writeString(file, new org.openqa.selenium.json.Json().toJson(data));
                eq(c, UserSearchPolicy.load(app, file), "configured exact UI schema");
                rejects("SEARCH_UI_CONTRACT_APP_MISMATCH", () -> UserSearchPolicy.load(app.equals("instagram") ? "tiktok" : "instagram", file));
                java.nio.file.Files.delete(file);
            } finally { java.nio.file.Files.delete(tmp); }
        }
        eq(true, UserSearchPolicy.exact("fixture_user", "@Fixture_User"), "case-insensitive exact match");
        for (String invalid : List.of("\u200efixture_user", "\u2068fixture_user\u2069", "fixture\u200e_user", "\u200e\u2068fixture_user2\u2069", "\u200e\u2068fıxture_user\u2069"))
            eq(false, UserSearchPolicy.exact("fixture_user", invalid), "only complete observed wrapper around exact ASCII handle");
        for (String other : List.of("fixture_user2", "fixture_user verified", "@fixture_user ", "fіxture_user", "fıxture_user", "prefix_fixture_user"))
            eq(false, UserSearchPolicy.exact("fixture_user", other), "no fuzzy, display suffix, whitespace, or confusable matching");
        return assertions;
    }
}
