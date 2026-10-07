package cn.local.githubcn;

/** Monotonic foreground dwell time; independent of Android for the small runnable check. */
public final class PageStay {
    public static final long DELAY_MS = 5000;
    private String page;
    private long since;
    private boolean foreground, restored;

    public void visit(String value, long now) {
        if (value == null) value = "";
        if (!value.equals(page)) { page = value; since = now; restored = false; }
    }
    public void foreground(boolean value, long now) {
        if (foreground == value) return;
        foreground = value;
        since = now;
        if (!value) restored = false;
    }
    public void hide(long now) { since = now; restored = false; }
    public void restore(long now) { since = now; restored = true; }
    public boolean visible(long now) { return foreground && page != null && (restored || now - since >= DELAY_MS); }
    public long remaining(long now) { return visible(now) ? 0 : Math.max(0, DELAY_MS - Math.max(0, now - since)); }
}
