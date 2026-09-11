using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
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
        internal static RuntimeAssetDescriptor BasicProbe()
        {
            RuntimeAssetDescriptor found = null;
            foreach (RuntimeAssetDescriptor asset in BuildConstants.RuntimeFiles)
            {
                if (!asset.ResourceName.StartsWith("D31FlashTool.Runtime.BasicProbe", StringComparison.Ordinal)) { continue; }
                if (found != null || !Regex.IsMatch(asset.ResourceName, @"^D31FlashTool\.Runtime\.BasicProbe[1-9][0-9]*$") ||
                    !Regex.IsMatch(asset.Sha256, @"\A[a-fA-F0-9]{64}\z") ||
                    Path.GetDirectoryName(asset.RelativePath) != "首次引导工具" ||
                    !Regex.IsMatch(Path.GetFileName(asset.RelativePath), @"\AD31-basic-v[A-Za-z0-9._-]+\.apk\z"))
                {
                    throw new InvalidDataException("基础探针内置资源合同无效。");
                }
                found = asset;
            }
            if (found == null) { throw new InvalidDataException("EXE缺少基础探针资源。"); }
            return found;
        }

        internal static string ValidateBasicProbe(string root)
        {
            RuntimeAssetDescriptor asset = BasicProbe();
            string path = Path.Combine(root, asset.RelativePath);
            if (!File.Exists(path) || !ComputeSha256(path).Equals(asset.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException("基础探针APK的SHA-256不匹配。");
            }
            return path;
        }

        internal static string ExportBasicProbe(string destination)
        {
            RuntimeAssetDescriptor asset = BasicProbe();
            string target = Path.GetFullPath(destination);
            string directory = Path.GetDirectoryName(target);
            if (!Directory.Exists(directory)) { throw new DirectoryNotFoundException("导出目录不存在。"); }
            if (File.Exists(target))
            {
                if (ComputeSha256(target).Equals(asset.Sha256, StringComparison.OrdinalIgnoreCase)) { return target; }
                throw new IOException("目标文件已存在且内容不同，请选择新的文件名。");
            }
            string temporary = target + ".tmp-" + Guid.NewGuid().ToString("N");
            try
            {
                // Export the verified embedded bytes, independent of older or damaged cache files.
                using (Stream resource = typeof(RuntimeAssets).Assembly.GetManifestResourceStream(asset.ResourceName))
                {
                    if (resource == null) { throw new InvalidDataException("EXE缺少基础探针资源内容。"); }
                    using (FileStream output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                    {
                        resource.CopyTo(output);
                    }
                }
                if (!ComputeSha256(temporary).Equals(asset.Sha256, StringComparison.OrdinalIgnoreCase))
                {
                    throw new InvalidDataException("导出的基础探针APK校验失败。");
                }
                File.Move(temporary, target);
                return target;
            }
            finally { if (File.Exists(temporary)) { File.Delete(temporary); } }
        }

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
