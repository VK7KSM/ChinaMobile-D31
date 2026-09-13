package net.elfradio.d31bootstrap.media;

/** 电话、SIP及其它音频占用由宿主共同否决；未知状态必须拒绝。 */
public interface AudioGuard {
    void requireIdle() throws Exception;
}
