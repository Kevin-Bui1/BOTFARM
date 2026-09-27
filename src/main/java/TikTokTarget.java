/** Reads the target from local configuration instead of committing account data. */
final class TikTokTarget {
    private TikTokTarget() {}

    static String username() {
        String value = System.getenv("TIKTOK_USERNAME");
        if (value == null || !value.matches("[A-Za-z0-9._]{1,24}")) {
            throw new IllegalArgumentException("Set TIKTOK_USERNAME to a username without the @ prefix");
        }
        return value;
    }
}
