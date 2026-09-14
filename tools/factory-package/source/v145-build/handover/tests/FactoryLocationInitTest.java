import java.io.*;
import java.nio.file.*;
import java.util.*;
import android.content.pm.IPackageManager;

public final class FactoryLocationInitTest {
    static int passed;
    static void check(boolean condition) { if(!condition) throw new AssertionError(); }
    static void pass(String name) { passed++; System.out.println("PASS " + name); }
    static void expectRejected(boolean apply) throws Exception {
        try { FactoryInit.locationDefaults(apply); throw new AssertionError(); }
        catch(IOException expected) { check(expected.getMessage().contains("location settings")); }
    }
    public static void main(String[] args) throws Exception {
        FactoryInit.ROOT=new File(args[0]);
        FactoryInit init=new FactoryInit();
        File required=new File(FactoryInit.ROOT,"factory-init-required");
        File done=new File(FactoryInit.ROOT,"factory-init-complete");
        IPackageManager pm=IPackageManager.INSTANCE;
        required.createNewFile();
        init.run(false,false);
        check(pm.writes==0 && HandoverRuntime.writes==0);
        pass("inspect_does_not_enable");

        HandoverRuntime.values.put("secure:location_mode","null");
        HandoverRuntime.values.put("secure:location_providers_allowed","gps");
        init.run(true,false);
        check(done.isFile() && !required.exists());
        check(HandoverRuntime.values.get("secure:location_mode").equals("3"));
        check(HandoverRuntime.values.get("secure:location_providers_allowed").equals("gps,network"));
        pass("fresh_install_and_complete_marker");
        for(String permission:new String[]{"CAMERA","RECORD_AUDIO","ACCESS_FINE_LOCATION","ACCESS_COARSE_LOCATION"})
            check(pm.granted.contains("net.elfradio.d31bootstrap:android.permission."+permission));
        check(pm.states.get("net.elfradio.d31bootstrap")==0);
        pass("existing_permissions_and_app_identity_retained");

        int writes=pm.writes+HandoverRuntime.writes;
        init.run(false,true);
        check(writes==pm.writes+HandoverRuntime.writes);
        pass("verify_is_read_only");

        HandoverRuntime.values.put("secure:location_providers_allowed","passive,gps");
        HandoverRuntime.values.put("global:wifi_on","0");
        HandoverRuntime.values.put("global:mobile_data","0");
        FactoryInit.locationDefaults(true);
        FactoryInit.locationDefaults(true);
        check(HandoverRuntime.values.get("secure:location_providers_allowed").equals("passive,gps,network"));
        check(HandoverRuntime.values.get("global:wifi_on").equals("0"));
        check(HandoverRuntime.values.get("global:mobile_data").equals("0"));
        pass("provider_addition_idempotent_and_network_switches_unchanged");

        HandoverRuntime.values.put("secure:location_mode","0");
        HandoverRuntime.values.put("secure:location_providers_allowed","");
        writes=HandoverRuntime.writes;
        expectRejected(false);
        check(writes==HandoverRuntime.writes);
        check(HandoverRuntime.values.get("secure:location_mode").equals("0"));
        pass("later_user_disable_not_reenabled_by_verify");

        for(String ignored:new String[]{"location_mode","location_providers_allowed:+gps","location_providers_allowed:+network"}) {
            done.delete();required.createNewFile();
            HandoverRuntime.values.put("secure:location_mode","0");
            HandoverRuntime.values.put("secure:location_providers_allowed","");
            HandoverRuntime.ignore=ignored;
            try { init.run(true,false); throw new AssertionError(); }
            catch(IOException expected) { check(expected.getMessage().contains("location settings")); }
            check(required.isFile() && !done.exists());
            pass("failed_write_keeps_init_request_"+ignored);
        }
        HandoverRuntime.ignore="";
        init.run(true,false);
        check(done.isFile() && !required.exists());
        pass("retry_after_failed_settings_completes");

        HandoverRuntime.values.put("secure:location_providers_allowed","agps,network");
        expectRejected(false);
        pass("provider_match_is_exact");

        HandoverRuntime.values.put("secure:location_providers_allowed","gps,network");
        for(String mode:new String[]{"null",""}) {
            HandoverRuntime.values.put("secure:location_mode",mode);
            writes=HandoverRuntime.writes;
            FactoryInit.locationDefaults(false);
            check(writes==HandoverRuntime.writes);
        }
        pass("derived_mode_with_both_providers_is_valid_and_read_only");
        HandoverRuntime.values.put("secure:location_providers_allowed","gps");
        writes=HandoverRuntime.writes;
        expectRejected(false);
        check(writes==HandoverRuntime.writes);
        check(HandoverRuntime.values.get("secure:location_providers_allowed").equals("gps"));
        pass("gps_only_not_overwritten_by_default_verification");

        check(!new File(FactoryInit.ROOT,"enabled").exists());
        check(!new File(FactoryInit.ROOT,"supervisor.json").exists());
        check(!new File(FactoryInit.ROOT,"stop-supervisor").exists());
        pass("no_update_runtime_files_created");
        System.out.println("PASSED="+passed);
    }
}
