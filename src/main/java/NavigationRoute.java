import java.util.*;

/** Fixed observed routes; progress can advance only through verified transitions in order. */
public final class NavigationRoute {
    private final List<NavigationPolicy.Destination> screens;
    private int completed;

    public NavigationRoute(String app, String goal) {
        if (goal.equals("search-to-profile") && Set.of("instagram", "tiktok").contains(app)) {
            screens = app.equals("instagram")
                    ? List.of(NavigationPolicy.Destination.HOME, NavigationPolicy.Destination.SEARCH, NavigationPolicy.Destination.PROFILE)
                    : List.of(NavigationPolicy.Destination.HOME, NavigationPolicy.Destination.SEARCH,
                            NavigationPolicy.Destination.HOME, NavigationPolicy.Destination.PROFILE);
        } else screens = List.of(NavigationPolicy.Destination.HOME, NavigationPolicy.goal(app, goal));
    }

    public List<NavigationPolicy.Destination> screens() { return screens; }
    public int completed() { return completed; }
    public boolean complete() { return completed == screens.size() - 1; }
    public NavigationPolicy.Destination source() {
        if (complete()) throw new IllegalStateException("ROUTE_ALREADY_COMPLETE");
        return screens.get(completed);
    }
    public NavigationPolicy.Destination next() {
        if (complete()) throw new IllegalStateException("ROUTE_ALREADY_COMPLETE");
        return screens.get(completed + 1);
    }
    public String description() { return String.join(" -> ", screens.stream().map(Enum::name).toList()); }
    public void requireSource(NavigationPolicy.Screen screen) {
        if (!screen.blocker().isEmpty()) throw new IllegalStateException(screen.blocker());
        if (screen.destination() != source()) throw new IllegalStateException("ROUTE_SOURCE_CHANGED");
    }
    public void advance(NavigationPolicy.Screen before, NavigationPolicy.Control action,
                        NavigationPolicy.Screen after) {
        requireSource(before);
        NavigationPolicy.requireGoal(next(), action);
        String verdict = NavigationPolicy.routeTransition(before, action, after);
        if (!verdict.equals("VERIFIED")) throw new IllegalStateException(verdict);
        completed++;
    }
}
