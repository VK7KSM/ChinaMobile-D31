using System;
using System.IO;

namespace D31FlashTool
{
    internal static class MaintenanceGuard
    {
        internal const string Absent = "D31_MAINTENANCE_ABSENT_V1";
        internal const string Present = "D31_MAINTENANCE_PRESENT_V1";
        // Check readable ancestors before treating a missing component as absence. Never read repair contents.
        internal const string Command = "[ \"$(id -u)\" = 0 ] || exit 70; p=/data/local; " +
            "for n in d31-remote runtime maintenance repair.json; do " +
            "[ -d \"$p\" ] && [ ! -L \"$p\" ] || exit 71; " +
            "names=$(busybox ls -a \"$p\") || exit 72; " +
            "printf '%s\\n' \"$names\" | busybox grep -Fx \"$n\" >/dev/null; r=$?; " +
            "if [ \"$r\" = 1 ]; then echo " + Absent + "; exit 0; fi; " +
            "[ \"$r\" = 0 ] || exit 73; p=\"$p/$n\"; done; echo " + Present;

        internal static void AssertAbsent(Func<string, string> read)
        {
            string result;
            try { result = read(Command); }
            catch (Exception error) { throw new IOException("无法确认D31维护状态，已阻止刷机。", error); }
            if (String.Equals((result ?? String.Empty).Trim(), Absent, StringComparison.Ordinal)) { return; }
            if (String.Equals((result ?? String.Empty).Trim(), Present, StringComparison.Ordinal))
            {
                throw new InvalidOperationException("D31存在尚未结束的系统修复任务，已阻止刷机。请等待修复完成后重新检查。");
            }
            throw new InvalidDataException("D31维护状态回执未知，已阻止刷机。");
        }
    }
}
