package net.elfradio.d31bootstrap;

final class DeferredAppStartup {
    private static final String RESCHEDULE_RECEIVER =
            "androidx.work.impl.background.systemalarm.RescheduleReceiver";

    static final App FIREFOX = new App("Firefox", "org.mozilla.firefox");
    static final App THUNDERBIRD = new App("Thunderbird", "net.thunderbird.android");

    static final class App {
        final String displayName;
        final String packageName;

        App(String displayName, String packageName) {
            this.displayName = displayName;
            this.packageName = packageName;
        }

        String componentName() {
            return packageName + "/" + RESCHEDULE_RECEIVER;
        }
    }

    private DeferredAppStartup() {
    }

    static AdbControl.ActionResult deferAtBoot() {
        AdbControl.ActionResult firefox = disableReceiver(FIREFOX);
        AdbControl.ActionResult thunderbird = disableReceiver(THUNDERBIRD);
        return new AdbControl.ActionResult(
                firefox.log + "\n" + thunderbird.log,
                firefox.succeeded && thunderbird.succeeded);
    }

    static AdbControl.ActionResult resume(App app) {
        java.util.List<String> commands = java.util.Arrays.asList(
                enableCommand(app), bootBroadcastCommand(app));
        return AdbControl.executeRootSequence(app.displayName + "后台恢复", commands);
    }

    static AdbControl.ActionResult disableReceiver(App app) {
        return AdbControl.executeRoot(app.displayName + "开机接收器收尾", disableCommand(app));
    }

    static String disableCommand(App app) {
        return "pm disable --user 0 " + app.componentName();
    }

    static String enableCommand(App app) {
        return "pm enable --user 0 " + app.componentName();
    }

    static String bootBroadcastCommand(App app) {
        return "am broadcast --user 0 -f 0x20 -a android.intent.action.BOOT_COMPLETED -n "
                + app.componentName();
    }
}
