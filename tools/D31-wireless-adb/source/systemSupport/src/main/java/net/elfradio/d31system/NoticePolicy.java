package net.elfradio.d31system;

final class NoticePolicy {
    static final String SMS = "net.elfradio.d31phone.debug";
    static boolean telegram(String name) {
        return "org.telegram.messenger.web".equals(name) || "org.telegram.messenger".equals(name);
    }
    static boolean message(String name, boolean ongoing, boolean summary, boolean smsMessage,
                           String category, boolean hasText) {
        if (ongoing || summary) return false;
        if (SMS.equals(name)) return smsMessage;
        return telegram(name) && hasText && (category == null || "msg".equals(category));
    }
    static boolean canShowBanner(boolean alert, boolean call, boolean silent, boolean interrupted) {
        return alert && !call && !silent && !interrupted;
    }
    static String bounded(CharSequence value, int limit) {
        if (value == null) return "";
        String text = value.toString().replace('\n', ' ').trim();
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}
