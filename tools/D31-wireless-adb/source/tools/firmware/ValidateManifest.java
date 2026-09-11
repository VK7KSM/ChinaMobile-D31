import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticManifest;
import org.json.JSONObject;

/** 独立使用既有Java合同解析生成结果；不调用Android或Gradle。 */
public final class ValidateManifest {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("必须显式指定清单文件");
        for (String path : args) {
            JSONObject input = new JSONObject(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8));
            JSONObject result = DiagnosticManifest.parse(input).toJson();
            if (!"FIRMWARE".equals(result.getString("role")) || !"PARTIAL".equals(result.getString("completeness"))) {
                throw new IllegalArgumentException("固件角色或局部范围状态错误");
            }
            System.out.println(new JSONObject().put("file", path).put("parsed", true)
                    .put("role", result.getString("role")).put("completeness", result.getString("completeness"))
                    .put("entries", result.getJSONArray("entries").length()).toString());
        }
    }
}
