namespace D31FlashTool
{
    internal static class BuildConstants
    {
        internal const string ToolVersion = "1.6.11";
        internal const string OfficialPackageName = "D31_SVP3390_Factory_Flash_v1.4.6_testkey.zip";
        internal const string OfficialGitHubUrl = "https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.4.6/D31_SVP3390_Factory_Flash_v1.4.6_testkey.zip";
        internal const string OfficialCloudflareUrl = "https://cdn.elfradio.net/d31/D31_SVP3390_Factory_Flash_v1.4.6_testkey.zip";
        internal const string OfficialPackageSha256 = "1BF50A5D00CE1571340EA3C1ADEAECA0067D502E7AD8E4E7A1C4DB4C7B7CD92B";
        internal const long OfficialPackageBytes = 1725719086L;
        internal const string RescueRestoreSha256 = "783D95431DCE93094347C5CEDA31F367102CEF1786DF4E18A171914C74322BC4";
        internal const string RescueTestSha256 = "012FBBA56C97AE9A8A2A7E034EAB5CAB396C8D7E096FFEEB9E551DE6713BFD18";
        internal const string Aria2Sha256 = "B9CD71B275AF11B63C33457B0F43F2F2675937070C563E195F223EFD7FA4C74B";
        internal static readonly RuntimeAssetDescriptor[] RuntimeFiles = new RuntimeAssetDescriptor[]
        {
            new RuntimeAssetDescriptor("approved-package.json", "D31FlashTool.Runtime.Asset00", "748158E26D72E4C371B1DD9784862E5F8954FC7149141DEA70EC183CBC4D0EF3"),
            new RuntimeAssetDescriptor("installed-files.json", "D31FlashTool.Runtime.Asset01", "566DF4C608147834D93B1531362DBF1EC6C254BE667C19687653851FC490E19D"),
            new RuntimeAssetDescriptor("flash_d31_recovery.ps1", "D31FlashTool.Runtime.Asset02", "DF09B9865EB161E4A965E8B1CEE88F6254B026739B289CAFA994127E307D9C6B"),
            new RuntimeAssetDescriptor("create_d31_rescue.ps1", "D31FlashTool.Runtime.Asset03", "9AEA40D69F97FDC3B2075F0DEFA6BF9BCD319C1A73EC9EEADE8B035DA72D1317"),
            new RuntimeAssetDescriptor("tools\\adb.exe", "D31FlashTool.Runtime.Asset04", "957E46B8615F7AF5B7292A2DDABE98D2E61940C3FB2B0545756507F080613E71"),
            new RuntimeAssetDescriptor("tools\\AdbWinApi.dll", "D31FlashTool.Runtime.Asset05", "120BEF587119C6CB926B86B9BE90FDFBCE38937588EAE28CD91A94CE63C7B965"),
            new RuntimeAssetDescriptor("tools\\AdbWinUsbApi.dll", "D31FlashTool.Runtime.Asset06", "6CA69A2CA0E31309C087D288F058977D421AD03500E4C3E1DBD981241A069C60"),
            new RuntimeAssetDescriptor("tools\\aria2c.exe", "D31FlashTool.Runtime.Asset07", "B9CD71B275AF11B63C33457B0F43F2F2675937070C563E195F223EFD7FA4C74B"),
            new RuntimeAssetDescriptor("tools\\aria2-COPYING.txt", "D31FlashTool.Runtime.Asset08", "8177F97513213526DF2CF6184D8FF986C675AFB514D4E68A404010521B880643"),
            new RuntimeAssetDescriptor("首次引导工具\\D31-setup-probe.apk", "D31FlashTool.Runtime.Asset09", "72FB636B23355BE70E66FF8A63F4D44135EFA06A3546CE9A1EC4A98E9BAD1132"),
            new RuntimeAssetDescriptor("首次引导工具\\D31-basic-v1.34.18-candidate-basic-193.apk", "D31FlashTool.Runtime.BasicProbe193", "C7E7665937B2895905CAF3B7A71DA6F65FA311DF601BEB771E767C34924D20A3"),
            new RuntimeAssetDescriptor("rescue\\D31_RESCUE_UPDATE.zip", "D31FlashTool.Runtime.Asset11", "783D95431DCE93094347C5CEDA31F367102CEF1786DF4E18A171914C74322BC4"),
            new RuntimeAssetDescriptor("rescue\\D31_RESCUE_TEST.zip", "D31FlashTool.Runtime.Asset12", "012FBBA56C97AE9A8A2A7E034EAB5CAB396C8D7E096FFEEB9E551DE6713BFD18")
        };
    }
}