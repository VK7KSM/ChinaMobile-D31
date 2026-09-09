namespace D31FlashTool
{
    internal static class BuildConstants
    {
        internal const string ToolVersion = "1.6.6";
        internal const string OfficialPackageSha256 = "9D44B71BE5077257D1B7B4CFE08EF4357EE0102AC3BDACB1CBC52ACF1394119A";
        internal const long OfficialPackageBytes = 1705489135L;
        internal const string RescueRestoreSha256 = "783D95431DCE93094347C5CEDA31F367102CEF1786DF4E18A171914C74322BC4";
        internal const string RescueTestSha256 = "012FBBA56C97AE9A8A2A7E034EAB5CAB396C8D7E096FFEEB9E551DE6713BFD18";
        internal const string Aria2Sha256 = "B9CD71B275AF11B63C33457B0F43F2F2675937070C563E195F223EFD7FA4C74B";
        internal static readonly RuntimeAssetDescriptor[] RuntimeFiles = new RuntimeAssetDescriptor[]
        {
            new RuntimeAssetDescriptor("approved-package.json", "D31FlashTool.Runtime.Asset00", "8C301666FE38BB66E5D7021226662C7700621DA6590DC5DB91326A630011EB03"),
            new RuntimeAssetDescriptor("installed-files.json", "D31FlashTool.Runtime.Asset01", "0A013BEBBA6BB6426E9FF69AC35403FB4A30D333E3608BC785FE8B8C4A033688"),
            new RuntimeAssetDescriptor("flash_d31_recovery.ps1", "D31FlashTool.Runtime.Asset02", "9FCE1962444365833AD06AB5180903ABDFA7112C6920DE0E37F2B78AF31AFD9F"),
            new RuntimeAssetDescriptor("create_d31_rescue.ps1", "D31FlashTool.Runtime.Asset03", "2A03304B1E8CD2FDF06031553A95533FC723C16DCBC86D129149915D0F8F333E"),
            new RuntimeAssetDescriptor("tools\\adb.exe", "D31FlashTool.Runtime.Asset04", "957E46B8615F7AF5B7292A2DDABE98D2E61940C3FB2B0545756507F080613E71"),
            new RuntimeAssetDescriptor("tools\\AdbWinApi.dll", "D31FlashTool.Runtime.Asset05", "120BEF587119C6CB926B86B9BE90FDFBCE38937588EAE28CD91A94CE63C7B965"),
            new RuntimeAssetDescriptor("tools\\AdbWinUsbApi.dll", "D31FlashTool.Runtime.Asset06", "6CA69A2CA0E31309C087D288F058977D421AD03500E4C3E1DBD981241A069C60"),
            new RuntimeAssetDescriptor("tools\\aria2c.exe", "D31FlashTool.Runtime.Asset07", "B9CD71B275AF11B63C33457B0F43F2F2675937070C563E195F223EFD7FA4C74B"),
            new RuntimeAssetDescriptor("tools\\aria2-COPYING.txt", "D31FlashTool.Runtime.Asset08", "8177F97513213526DF2CF6184D8FF986C675AFB514D4E68A404010521B880643"),
            new RuntimeAssetDescriptor("首次引导工具\\D31-setup-probe.apk", "D31FlashTool.Runtime.Asset09", "72FB636B23355BE70E66FF8A63F4D44135EFA06A3546CE9A1EC4A98E9BAD1132"),
            new RuntimeAssetDescriptor("首次引导工具\\D31-wireless-adb-v1.11.6.apk", "D31FlashTool.Runtime.Asset10", "2B52229010CFD4E704BE9EB386C4D0157FF73DF055AFBE961B8B848F8029A97F"),
            new RuntimeAssetDescriptor("rescue\\D31_RESCUE_UPDATE.zip", "D31FlashTool.Runtime.Asset11", "783D95431DCE93094347C5CEDA31F367102CEF1786DF4E18A171914C74322BC4"),
            new RuntimeAssetDescriptor("rescue\\D31_RESCUE_TEST.zip", "D31FlashTool.Runtime.Asset12", "012FBBA56C97AE9A8A2A7E034EAB5CAB396C8D7E096FFEEB9E551DE6713BFD18")
        };
    }
}