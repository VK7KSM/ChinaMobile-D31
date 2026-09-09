namespace D31FlashTool
{
    internal static class BuildConstants
    {
        internal const string ToolVersion = "1.6.4";
        internal const string OfficialPackageSha256 = "FC377E30FB73A26DCC1A00BFD7AA5E5F14279A4FA72B260F6F3D864D34296EAB";
        internal const long OfficialPackageBytes = 1705392433L;
        internal const string RescueRestoreSha256 = "783D95431DCE93094347C5CEDA31F367102CEF1786DF4E18A171914C74322BC4";
        internal const string RescueTestSha256 = "012FBBA56C97AE9A8A2A7E034EAB5CAB396C8D7E096FFEEB9E551DE6713BFD18";
        internal const string Aria2Sha256 = "B9CD71B275AF11B63C33457B0F43F2F2675937070C563E195F223EFD7FA4C74B";
        internal static readonly RuntimeAssetDescriptor[] RuntimeFiles = new RuntimeAssetDescriptor[]
        {
            new RuntimeAssetDescriptor("approved-package.json", "D31FlashTool.Runtime.Asset00", "69956FE831414D6F6D6D3C60CCB1869641EF8A26919692E140D2938B639DCC6C"),
            new RuntimeAssetDescriptor("installed-files.json", "D31FlashTool.Runtime.Asset01", "A58217051DD539AC50F74576077CC20198BC94F2768F4E2B70420CC5F369A235"),
            new RuntimeAssetDescriptor("flash_d31_recovery.ps1", "D31FlashTool.Runtime.Asset02", "27B869FBE5D9521C385E1238F6AB18438A71EE31D28E26D477E881E36DB2E956"),
            new RuntimeAssetDescriptor("create_d31_rescue.ps1", "D31FlashTool.Runtime.Asset03", "2A03304B1E8CD2FDF06031553A95533FC723C16DCBC86D129149915D0F8F333E"),
            new RuntimeAssetDescriptor("tools\\adb.exe", "D31FlashTool.Runtime.Asset04", "957E46B8615F7AF5B7292A2DDABE98D2E61940C3FB2B0545756507F080613E71"),
            new RuntimeAssetDescriptor("tools\\AdbWinApi.dll", "D31FlashTool.Runtime.Asset05", "120BEF587119C6CB926B86B9BE90FDFBCE38937588EAE28CD91A94CE63C7B965"),
            new RuntimeAssetDescriptor("tools\\AdbWinUsbApi.dll", "D31FlashTool.Runtime.Asset06", "6CA69A2CA0E31309C087D288F058977D421AD03500E4C3E1DBD981241A069C60"),
            new RuntimeAssetDescriptor("tools\\aria2c.exe", "D31FlashTool.Runtime.Asset07", "B9CD71B275AF11B63C33457B0F43F2F2675937070C563E195F223EFD7FA4C74B"),
            new RuntimeAssetDescriptor("tools\\aria2-COPYING.txt", "D31FlashTool.Runtime.Asset08", "8177F97513213526DF2CF6184D8FF986C675AFB514D4E68A404010521B880643"),
            new RuntimeAssetDescriptor("首次引导工具\\D31-setup-probe.apk", "D31FlashTool.Runtime.Asset09", "72FB636B23355BE70E66FF8A63F4D44135EFA06A3546CE9A1EC4A98E9BAD1132"),
            new RuntimeAssetDescriptor("首次引导工具\\D31-wireless-adb-v1.11.5.apk", "D31FlashTool.Runtime.Asset10", "FF4ADA4BF6BA0002CFA42F3C96A8E6ACF1B8AB6A5D84EE0F63D5A5ADB3E54895"),
            new RuntimeAssetDescriptor("rescue\\D31_RESCUE_UPDATE.zip", "D31FlashTool.Runtime.Asset11", "783D95431DCE93094347C5CEDA31F367102CEF1786DF4E18A171914C74322BC4"),
            new RuntimeAssetDescriptor("rescue\\D31_RESCUE_TEST.zip", "D31FlashTool.Runtime.Asset12", "012FBBA56C97AE9A8A2A7E034EAB5CAB396C8D7E096FFEEB9E551DE6713BFD18")
        };
    }
}