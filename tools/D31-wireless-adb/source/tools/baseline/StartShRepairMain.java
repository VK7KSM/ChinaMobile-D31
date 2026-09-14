import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.repair.RepairPlan;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticManifest;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticCoverageComparison;

/** 仅在宿主调用现有合同，不实现另一套摘要或比较算法。 */
public final class StartShRepairMain {
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = System.in.read(buffer)) != -1) {
            if (data.size() + n > 8 * 1024 * 1024) throw new IllegalArgumentException("输入超限");
            data.write(buffer, 0, n);
        }
        JSONObject input = new JSONObject(new String(data.toByteArray(), StandardCharsets.UTF_8));
        if (args.length == 1 && args[0].equals("plan")) {
            RepairPlan p = RepairPlan.fromJson(input);
            System.out.println(new JSONObject().put("plan", p.toJson()).put("plan_sha256", p.sha256()));
        } else if (args.length == 1 && args[0].equals("compare")) {
            System.out.println(new DiagnosticCoverageComparison().compare(
                DiagnosticManifest.parse(input.getJSONObject("observation")),
                DiagnosticManifest.parse(input.getJSONObject("firmware")), input.getLong("now")));
        } else throw new IllegalArgumentException("仅支持plan或compare");
    }
}
