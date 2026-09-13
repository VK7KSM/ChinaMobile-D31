package net.elfradio.d31bootstrap.diagnostics.collection;

import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;
import static net.elfradio.d31bootstrap.diagnostics.collection.CollectionSupport.*;

/** 仅识别已审查启动脚本的ROOT配置；不执行脚本，不推断守护是否运行。 */
final class SystemSupportConfiguration {
    static final String PATH = "/data/local/d31-system-support/start.sh";
    static final String FIELD = "semantic.system_support.root";
    static final int MAX_BYTES = 4096;
    private static final String PREFIX = "#!/system/bin/sh\nROOT=";
    // 历史520字节脚本只将ROOT值替换成<ROOT>后的完整字节摘要。
    private static final String TEMPLATE_SHA256 = "8c94b006210e3ac9f0ebabc301d72c2e593c4e6fed003db555f9bbe3a3f0adc8";
    private static final String SOURCE_TEMPLATE_SHA256 = "01bb6df85c9c9e7781f28461fc985c2d96e2f54179a799375ea4008509792d1c";

    static JSONObject evidence(byte[] bytes, String source) throws JSONException {
        if (bytes == null || bytes.length > MAX_BYTES)
            return missing("NOT_CHECKED", "CONFIGURATION_BYTE_LIMIT", source);
        String script = new String(bytes, StandardCharsets.UTF_8);
        int end = script.indexOf('\n', PREFIX.length());
        if (!script.startsWith(PREFIX) || end < 0)
            return missing("NOT_CHECKED", "SYSTEM_SUPPORT_SCRIPT_SHAPE_NOT_RECOGNIZED", source);
        String root = script.substring(PREFIX.length(), end);
        if (!root.matches("/data/local/[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            return missing("NOT_CHECKED", "SYSTEM_SUPPORT_ROOT_NOT_SUPPORTED", source);
        String masked = PREFIX + "<ROOT>" + script.substring(end);
        String hash = hex(digest().digest(masked.getBytes(StandardCharsets.UTF_8)));
        if (!TEMPLATE_SHA256.equals(hash) && !SOURCE_TEMPLATE_SHA256.equals(hash))
            return missing("NOT_CHECKED", "SYSTEM_SUPPORT_SCRIPT_SHAPE_NOT_RECOGNIZED", source);
        return observed(root, source);
    }

    private SystemSupportConfiguration() { }
}
