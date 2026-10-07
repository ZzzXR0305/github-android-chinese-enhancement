package cn.local.githubcn;

public final class PageStayCheck {
    public static void main(String[] args) {
        PageStay timer = new PageStay();
        timer.visit("Activity/Fragment A/Title A", 0);
        timer.foreground(true, 0);
        check(!timer.visible(4999) && timer.visible(5000), "first page waits exactly five seconds");
        timer.hide(6000);
        timer.visit("Activity/Fragment A/Title A", 7400);
        check(!timer.visible(10999) && timer.visible(11000), "same-page polling cannot cancel long-press hiding");
        timer.visit("Activity/Fragment B/Title B", 12000);
        check(!timer.visible(16999) && timer.visible(17000), "a fragment or title change restarts dwell time");
        timer.foreground(false, 18000);
        check(!timer.visible(999999), "background never shows a button");
        timer.foreground(true, 30000);
        check(!timer.visible(34999) && timer.visible(35000), "background time does not count as page dwell");
        timer.hide(36000);
        timer.restore(36100);
        check(timer.visible(36100), "menu restore is immediate");
        timer.foreground(false, 37000);
        timer.restore(38000);
        check(!timer.visible(38000), "restoring while a menu covers the window stays hidden");
        timer.foreground(true, 39000);
        check(timer.visible(39000), "menu restore appears when window focus returns");
        timer.visit("new page", 40000);
        check(!timer.visible(44999) && timer.remaining(44000) == 1000, "new page clears manual restore");
        System.out.println("PASS: 5s dwell, long-press reset, fragment/title changes, background and menu restore");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
