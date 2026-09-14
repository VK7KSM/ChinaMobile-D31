import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticCoverageComparison;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticManifest;
import org.json.JSONObject;

/** 仅供宿主脚本调用；宿主先完成有界严格JSON读取。 */
public final class CoverageMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("参数：采集清单 固件清单 新报告 时间毫秒");
        JSONObject observation = new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8));
        JSONObject firmware = new JSONObject(new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8));
        JSONObject result = new DiagnosticCoverageComparison().compare(DiagnosticManifest.parse(observation),
                DiagnosticManifest.parse(firmware), Long.parseLong(args[3]));
        Files.write(Paths.get(args[2]), result.toString(2).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    }
}
