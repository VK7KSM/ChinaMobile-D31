package net.elfradio.d31bootstrap;

/**
 * 二维码窗口只有完整版带（基础版是探针，不放二维码编码器）。
 *
 * 核心是以 CLASSPATH 指向同一个 APK 起来的，所以类在不在就等于这个变体带不带这个功能，
 * 不需要 Context 也不用查包管理器。能力位据此上报，服务端就不会往没有窗口的设备派这类任务。
 *
 * 说实话一句：按 build.gradle 现在的划分，基础版只打包那 11 个白名单类，连核心本身都没有，
 * 所以这个检查在今天的任何一个能跑到这里的构建里都是真。留着是为了将来重新划分源码时，
 * 不至于悄无声息地上报一个没有窗口兜底的能力位——那种错只会在真派了任务下来时才暴露。
 */
final class ShareLinkAvailability {
    static final String ACTIVITY = "net.elfradio.d31bootstrap.ShareLinkActivity";
    private static Boolean cached;

    static synchronized boolean packaged() {
        if (cached == null) {
            try { Class.forName(ACTIVITY, false, ShareLinkAvailability.class.getClassLoader()); cached = true; }
            catch (Throwable absent) { cached = false; }
        }
        return cached;
    }

    private ShareLinkAvailability() {}
}
