namespace D31FlashTool
{
    internal static class BuildConstants
    {
        internal const string ToolVersion = "1.6.8";
        internal const string OfficialPackageName = "D31_SVP3390_Factory_Flash_v1.4.5_testkey.zip";
        internal const string OfficialGitHubUrl = "https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.4.5/D31_SVP3390_Factory_Flash_v1.4.5_testkey.zip";
        internal const string OfficialCloudflareUrl = "https://cdn.elfradio.net/d31/D31_SVP3390_Factory_Flash_v1.4.5_testkey.zip";
        internal const string OfficialPackageSha256 = "E74EFFC90A36EA9C7149532A7FFA7D556A448462324410BE7AA689DAF583CFC1";
        internal const long OfficialPackageBytes = 1725713383L;
        internal const string RescueRestoreSha256 = "783D95431DCE93094347C5CEDA31F367102CEF1786DF4E18A171914C74322BC4";
        internal const string RescueTestSha256 = "012FBBA56C97AE9A8A2A7E034EAB5CAB396C8D7E096FFEEB9E551DE6713BFD18";
        internal const string Aria2Sha256 = "B9CD71B275AF11B63C33457B0F43F2F2675937070C563E195F223EFD7FA4C74B";
        internal static readonly RuntimeAssetDescriptor[] RuntimeFiles = new RuntimeAssetDescriptor[]
        {
            new RuntimeAssetDescriptor("approved-package.json", "D31FlashTool.Runtime.Asset00", "D3EC0960435F16F68D5B03F0ADB4B411E855DCF531B48FD4A4992C8702E1A61A"),
            new RuntimeAssetDescriptor("installed-files.json", "D31FlashTool.Runtime.Asset01", "566DF4C608147834D93B1531362DBF1EC6C254BE667C19687653851FC490E19D"),
            new RuntimeAssetDescriptor("flash_d31_recovery.ps1", "D31FlashTool.Runtime.Asset02", "A8D6782D4DB37EF587F8E65A10353B1AEE4FD2BD7B1FCA8A379F9376290B5CB7"),
            new RuntimeAssetDescriptor("create_d31_rescue.ps1", "D31FlashTool.Runtime.Asset03", "2A03304B1E8CD2FDF06031553A95533FC723C16DCBC86D129149915D0F8F333E"),
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