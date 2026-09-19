package net.elfradio.d31bootstrap;

/**
 * 分享链接的业务结果取值。放在 main 源集，核心与完整版的显示窗口共用同一套字符串。
 * 这些值只进 result.share_link.outcome，不能当作任务状态机的 state。
 */
final class ShareLinkOutcome {
    static final String SHOWN = "shown";
    static final String DISMISSED = "dismissed";
    static final String EXPIRED = "expired";
    static final String SUPERSEDED = "superseded";
    static final String FAILED = "failed";
    private ShareLinkOutcome() {}
}
