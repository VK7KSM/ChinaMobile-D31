using System;
using System.Reflection;

namespace D31FlashTool
{
    public static class BasicProbeRuntimeHarness
    {
        public static void Extract(string directory)
        {
            MethodInfo method = typeof(RuntimeAssets).GetMethod("ExtractAll", BindingFlags.NonPublic | BindingFlags.Static);
            try { method.Invoke(null, new object[] { directory }); }
            catch (TargetInvocationException error) { throw error.InnerException; }
        }
    }
}
