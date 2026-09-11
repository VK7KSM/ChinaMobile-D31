package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONTokener;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticComparator;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticContract;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticManifest;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticRules;

/** 对已取得的清单做离线比较，不采集设备、不执行修复。 */
public final class DiagnosticCompareMain {
    private static JSONObject read(String path) throws Exception {
        StringBuilder text = new StringBuilder();
        try (Reader reader = new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int size;
            while ((size = reader.read(buffer)) != -1) {
                if (text.length() + size > DiagnosticContract.MAX_TEXT_CHARS)
                    throw new IOException("输入超出单份清单上限");
                text.append(buffer, 0, size);
            }
        }
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') text.deleteCharAt(0);
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > 12) throw new IOException("输入嵌套超出清单上限");
            } else if (c == '}' || c == ']') depth--;
        }
        JSONTokener tokener = new JSONTokener(text.toString());
        JSONObject value = new JSONObject(tokener);
        if (tokener.nextClean() != 0) throw new IOException("清单含额外JSON内容");
        return value;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5 && args.length != 6)
            throw new IOException("参数：开发板清单 固件清单 目标清单 规则 新报告路径 [比较时间毫秒]");
        long now = args.length == 6 ? Long.parseLong(args[5]) : System.currentTimeMillis();
        JSONObject result;
        boolean rejected = false;
        try {
            result = new DiagnosticComparator().compare(
                    DiagnosticManifest.parse(read(args[0])), DiagnosticManifest.parse(read(args[1])),
                    DiagnosticManifest.parse(read(args[2])), DiagnosticRules.parse(read(args[3])), now);
        } catch (DiagnosticContract.Invalid invalid) {
            result = invalid.toJson();
            rejected = true;
        }
        File destination = new File(args[4]).getAbsoluteFile();
        if (!destination.getParentFile().isDirectory() || !destination.createNewFile())
            throw new IOException("报告目录不存在或已有同名文件，禁止覆盖");
        try (FileOutputStream out = new FileOutputStream(destination)) {
            out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (rejected) {
            System.err.println("清单校验拒绝，已保存未比较报告");
            System.exit(2);
        }
        System.out.println("比较报告已生成；一致性结论以报告状态及未检查项目为准");
    }
}
