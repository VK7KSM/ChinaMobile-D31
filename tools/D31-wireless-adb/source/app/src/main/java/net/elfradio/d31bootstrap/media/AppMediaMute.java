package net.elfradio.d31bootstrap.media;

/** WebRTC的静音setter会打印日志；只在目标状态变化时调用。 */
final class AppMediaMute {
    interface Setter { void set(boolean muted); }
    private Boolean applied;
    private boolean sealed;
    synchronized void apply(boolean muted,Setter setter){
        if(sealed&&!muted)return;
        if(applied!=null&&applied.booleanValue()==muted)return;
        setter.set(muted);applied=muted;
    }
    synchronized void close(Setter setter){sealed=true;apply(true,setter);}
}
