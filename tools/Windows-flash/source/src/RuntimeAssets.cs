using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Security.Cryptography;
using System.Threading;

namespace D31FlashTool
{
    internal sealed class RuntimeAssetDescriptor
    {
        internal readonly string RelativePath;
        internal readonly string ResourceName;
        internal readonly string Sha256;

        internal RuntimeAssetDescriptor(string relativePath, string resourceName, string sha256)
        {
            RelativePath = relativePath;
            ResourceName = resourceName;
            Sha256 = sha256;
        }
    }

    internal static class RuntimeAssets
    {
        internal static string Prepare()
        {
            string root = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Elfradio",
                "D31FlashTool",
                BuildConstants.ToolVersion);
            Directory.CreateDirectory(root);

            using (Mutex mutex = new Mutex(false, "Local\\Elfradio.D31FlashTool." + BuildConstants.ToolVersion))
            {
                if (!mutex.WaitOne(TimeSpan.FromSeconds(30)))
                {
                    throw new TimeoutException("等待刷机工具运行文件初始化超时，请关闭其他D31刷机工具窗口后重试。");
                }
                try
                {
                    ExtractAll(root);
                }
                finally
                {
                    mutex.ReleaseMutex();
                }
            }
            return root;
        }

        private static void ExtractAll(string root)
        {
            Assembly assembly = typeof(RuntimeAssets).Assembly;
            foreach (RuntimeAssetDescriptor asset in BuildConstants.RuntimeFiles)
            {
                string destination = Path.Combine(root, asset.RelativePath);
                if (File.Exists(destination) &&
                    ComputeSha256(destination).Equals(asset.Sha256, StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                string directory = Path.GetDirectoryName(destination);
                if (!String.IsNullOrWhiteSpace(directory)) { Directory.CreateDirectory(directory); }
                string temporary = destination + ".tmp-" + Guid.NewGuid().ToString("N");
                try
                {
                    using (Stream resource = assembly.GetManifestResourceStream(asset.ResourceName))
                    {
                        if (resource == null)
                        {
                            throw new InvalidDataException("EXE内缺少运行资源：" + asset.RelativePath);
                        }
                        using (FileStream output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                        {
                            resource.CopyTo(output);
                        }
                    }
                    if (!ComputeSha256(temporary).Equals(asset.Sha256, StringComparison.OrdinalIgnoreCase))
                    {
                        throw new InvalidDataException("EXE内运行资源校验失败：" + asset.RelativePath);
                    }
                    if (File.Exists(destination)) { File.Delete(destination); }
                    File.Move(temporary, destination);
                }
                finally
                {
                    if (File.Exists(temporary)) { File.Delete(temporary); }
                }
            }
        }

        private static string ComputeSha256(string path)
        {
            using (SHA256 sha256 = SHA256.Create())
            using (FileStream stream = File.OpenRead(path))
            {
                return BitConverter.ToString(sha256.ComputeHash(stream)).Replace("-", String.Empty);
            }
        }
    }
}
