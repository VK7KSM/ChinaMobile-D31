public final class PackageState {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("net.elfradio.d31bootstrap") || args[0].equals("net.elfradio.d31system"))) throw new IllegalArgumentException();
        if ((Integer)Class.forName("android.system.Os").getMethod("getuid").invoke(null) != 0) throw new SecurityException();
        Object pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
        Class<?> api = Class.forName("android.content.pm.IPackageManager");
        if (!args[1].equals("read")) {
            int value = Integer.parseInt(args[1]);
            if (value < 0 || value > 2) throw new IllegalArgumentException();
            api.getMethod("setApplicationEnabledSetting", String.class, int.class, int.class, int.class, String.class)
                .invoke(pm, args[0], value, 0, 0, "shell");
        }
        System.out.println("PACKAGE_STATE=" + api.getMethod("getApplicationEnabledSetting", String.class, int.class).invoke(pm, args[0], 0));
        System.exit(0);
    }
}
