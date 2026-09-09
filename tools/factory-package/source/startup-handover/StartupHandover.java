import java.lang.reflect.Method;

public final class StartupHandover {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !"--inspect".equals(args[0]))
            throw new IllegalArgumentException("Only --inspect is supported");
        Class<?> appGlobals = Class.forName("android.app.AppGlobals");
        Object pm = appGlobals.getMethod("getPackageManager").invoke(null);
        Class<?> api = Class.forName("android.content.pm.IPackageManager");
        for (Method method : api.getMethods()) {
            if (method.getName().equals("getApplicationEnabledSetting")
                    || method.getName().equals("setApplicationEnabledSetting"))
                System.out.println("API=" + method.toString());
        }
        Method get = api.getMethod("getApplicationEnabledSetting", String.class, int.class);
        for (String pkg : new String[]{"com.starnet.nexui", "com.starnet.cmcc.imscc", "com.starnet.getnumber"})
            System.out.println("STATE " + pkg + "=" + get.invoke(pm, pkg, 0));
        System.out.println("INSPECT_ONLY_NO_CHANGES");
    }
}
