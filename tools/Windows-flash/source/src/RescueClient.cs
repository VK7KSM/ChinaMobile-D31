using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

namespace D31FlashTool
{
    internal static class RescueClient
    {
        // uptool按分号拆入200字节槽；保持单条、无分号且不足200字节。
        // 先读持久值再读运行值，最后一个有效值优先；输出只可能是十进制端口。
        // p仅为awk生成的有效十进制整数，省略其引号节约2字节，保留异步stop后的既有1秒等待。
        internal const string StartAdb = "p=$( (getprop persist.adb.tcp.port&&getprop service.adb.tcp.port)|busybox awk '/^[0-9]+$/&&$0>0&&$0<65536{p=$0+0}END{print p?p:5555}')&&setprop service.adb.tcp.port $p&&stop adbd&&sleep 1&&start adbd";
        internal const string ReadAdb = "echo D31_ADB_PORT_V1 && getprop service.adb.tcp.port && getprop persist.adb.tcp.port && getprop init.svc.adbd && echo D31_ADB_PORT_END";
        internal const string RestoreAdb = StartAdb + " && sleep 2 && " + ReadAdb;

        internal static int ValidPort(string value)
        {
            int port;
            return !String.IsNullOrEmpty(value) && value.All(c => c >= '0' && c <= '9') &&
                Int32.TryParse(value, out port) && port > 0 && port <= 65535 ? port : 0;
        }

        internal static int SelectPort(string service, string persistent)
        {
            int port = ValidPort(service);
            if (port == 0) port = ValidPort(persistent);
            return port == 0 ? 5555 : port;
        }

        internal static int PortFromReceipt(string receipt)
        {
            var result = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(receipt);
            RequireCompleted(result);
            object output;
            if (!result.TryGetValue("output", out output)) throw new IOException("探针未返回ADB端口快照。");
            string[] lines = Convert.ToString(output).Replace("\r", "").TrimEnd('\n').Split('\n');
            if (lines.Length != 5 || lines[0] != "D31_ADB_PORT_V1" || lines[4] != "D31_ADB_PORT_END")
                throw new IOException("探针端口快照缺失或不完整，不能确认恢复端口。");
            return SelectPort(lines[1], lines[2]);
        }

        internal static int ReadAdbPort(string host) { return PortFromReceipt(Execute(host, ReadAdb)); }

        private static void RequireCompleted(Dictionary<string, object> result)
        {
            object state, exitCode, truncated;
            if (result == null || !result.TryGetValue("state", out state) || Convert.ToString(state) != "completed" ||
                !result.TryGetValue("exit_code", out exitCode) || exitCode == null || Convert.ToString(exitCode) != "0" ||
                (result.TryGetValue("truncated", out truncated) && Convert.ToBoolean(truncated)))
                throw new IOException("探针任务未确认完成；不重放恢复命令，也不将属性当作ADB握手结果。");
        }
        internal static string ValidateHost(string host)
        {
            IPAddress ip;
            if (!IPAddress.TryParse(host, out ip) || ip.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork)
                throw new ArgumentException("请输入D31的局域网IPv4地址。");
            byte[] b = ip.GetAddressBytes();
            if (!(b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)))
                throw new ArgumentException("急救仅支持局域网IPv4地址。");
            return ip.ToString();
        }

        internal static Dictionary<string, object> Request(string host, string path, object body)
        {
            var json = new JavaScriptSerializer { MaxJsonLength = 262144 };
            var request = (HttpWebRequest)WebRequest.Create("http://" + ValidateHost(host) + ":8765" + path);
            request.Proxy = null;
            request.AllowAutoRedirect = false;
            request.Timeout = 5000;
            request.ReadWriteTimeout = 5000;
            if (body != null)
            {
                request.Method = "POST";
                request.ContentType = "application/json; charset=utf-8";
                byte[] data = Encoding.UTF8.GetBytes(json.Serialize(body));
                request.ContentLength = data.Length;
                using (var stream = request.GetRequestStream()) stream.Write(data, 0, data.Length);
            }
            using (var response = request.GetResponse())
            using (var reader = new StreamReader(response.GetResponseStream(), Encoding.UTF8))
            {
                char[] buffer = new char[262145];
                int count = 0, n;
                while (count < buffer.Length && (n = reader.Read(buffer, count, buffer.Length - count)) > 0) count += n;
                if (count == buffer.Length) throw new IOException("探针响应超过大小限制。");
                return json.Deserialize<Dictionary<string, object>>(new string(buffer, 0, count));
            }
        }

        internal static string Health(string host)
        {
            var result = Request(host, "/health", null);
            object uid;
            if (!result.TryGetValue("uid", out uid) || Convert.ToString(uid) != "0")
                throw new IOException("8765不是可用的root命令探针，请安装新版D31无线ADB工具并启用救援服务。");
            return new JavaScriptSerializer().Serialize(result);
        }

        internal static string Execute(string host, string command)
        {
            Health(host);
            return ExecuteCore(command, (path, body) => Request(host, path, body), () => Thread.Sleep(250));
        }

        internal static string ExecuteCore(string command, Func<string, object, Dictionary<string, object>> request, Action wait)
        {
            string id = Guid.NewGuid().ToString();
            Dictionary<string, object> result;
            try { result = request("/exec", new { id = id, command = command, timeout = 30 }); }
            catch (WebException)
            {
                // POST回执丢失时查询原任务，不能新建任务重放写操作。
                try { result = request("/jobs/" + id, null); }
                catch (Exception ex) { throw new IOException("恢复命令回执未知，原任务号：" + id + "；不自动重发。", ex); }
            }
            var watch = Stopwatch.StartNew();
            object state;
            while (result != null && result.TryGetValue("state", out state) && Convert.ToString(state) == "running" && watch.Elapsed.TotalSeconds < 45)
            {
                wait();
                try { result = request("/jobs/" + id, null); }
                catch (Exception ex) { throw new IOException("任务查询回执未知，原任务号：" + id + "；不自动重发。", ex); }
            }
            try { RequireCompleted(result); }
            catch (IOException ex) { throw new IOException("原任务号：" + id + "；" + ex.Message, ex); }
            return new JavaScriptSerializer().Serialize(result);
        }
    }

    internal sealed class RescueAdapter
    {
        internal NetworkInterface Nic;
        public override string ToString() { return Nic.Name + " · " + Nic.Description; }
    }

    internal static class UptoolClient
    {
        [StructLayout(LayoutKind.Sequential)]
        private struct Bpf { public uint Length; public IntPtr Instructions; }
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr LoadLibraryEx(string path, IntPtr file, uint flags);
        [DllImport("wpcap.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern IntPtr pcap_open_live(string name, int snaplen, int promisc, int timeout, StringBuilder error);
        [DllImport("wpcap.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern int pcap_datalink(IntPtr handle);
        [DllImport("wpcap.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern int pcap_setnonblock(IntPtr handle, int enabled, StringBuilder error);
        [DllImport("wpcap.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern int pcap_compile(IntPtr handle, ref Bpf program, string expression, int optimize, uint mask);
        [DllImport("wpcap.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern int pcap_setfilter(IntPtr handle, ref Bpf program);
        [DllImport("wpcap.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern void pcap_freecode(ref Bpf program);
        [DllImport("wpcap.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern int pcap_sendpacket(IntPtr handle, byte[] data, int length);
        [DllImport("wpcap.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern int pcap_next_ex(IntPtr handle, out IntPtr header, out IntPtr data);
        [DllImport("wpcap.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern void pcap_close(IntPtr handle);

        internal static RescueAdapter[] Adapters()
        {
            return NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up &&
                n.NetworkInterfaceType == NetworkInterfaceType.Ethernet && n.GetPhysicalAddress().GetAddressBytes().Length == 6)
                .Select(n => new RescueAdapter { Nic = n }).ToArray();
        }
        internal static byte[] Mac(string text)
        {
            string compact = text.Replace(":", "").Replace("-", "").Trim();
            if (compact.Length != 12) throw new ArgumentException("请输入D31有线网卡MAC地址（12位十六进制）。");
            var b = Enumerable.Range(0, 6).Select(i => Convert.ToByte(compact.Substring(i * 2, 2), 16)).ToArray();
            if ((b[0] & 1) != 0 || b.All(v => v == 0)) throw new ArgumentException("目标必须是有效的单播MAC地址。");
            return b;
        }
        internal static uint Session()
        {
            byte[] b = new byte[4];
            using (var rng = RandomNumberGenerator.Create()) rng.GetBytes(b);
            // 原厂main以有符号整数判断ug_action_judge的返回值，最高位必须为0。
            return (BitConverter.ToUInt32(b, 0) & 0x7fffffffu) | 0x10000u;
        }
        private static void Put(byte[] b, int offset, uint value, int length)
        { for (int i = length - 1; i >= 0; i--) { b[offset + i] = (byte)value; value >>= 8; } }

        internal static byte[] Frame(byte[] source, byte[] target, uint session, bool restore)
        {
            if (session == 0 || session > Int32.MaxValue) throw new ArgumentException("uptool会话号必须为正整数。");
            if (source.Length != 6 || target.Length != 6 || source.SequenceEqual(target) ||
                (source[0] & 1) != 0 || (target[0] & 1) != 0 || source.All(v => v == 0) || target.All(v => v == 0))
                throw new ArgumentException("源地址和目标地址必须是不同的单播MAC。");
            byte[] command = Encoding.ASCII.GetBytes(RescueClient.StartAdb);
            if (command.Length >= 200 || RescueClient.StartAdb.Contains(";"))
                throw new InvalidOperationException("uptool恢复命令超出已验证的单槽合同。");
            int length = restore ? command.Length + 4 : 0;
            byte[] body = new byte[16 + length];
            Put(body, 0, restore ? 0x0301u : 0x0101u, 2);
            Put(body, 2, session, 4);
            Put(body, 14, (uint)length, 2);
            if (restore) { body[16] = 1; Put(body, 17, (uint)command.Length, 2); Array.Copy(command, 0, body, 19, command.Length); }
            byte[] frame = new byte[Math.Max(60, 18 + body.Length)];
            Array.Copy(target, 0, frame, 0, 6); Array.Copy(source, 0, frame, 6, 6);
            Put(frame, 12, 0x9974, 2);
            using (var md5 = MD5.Create()) Array.Copy(md5.ComputeHash(body), 0, frame, 14, 4);
            Array.Copy(body, 0, frame, 18, body.Length);
            return frame;
        }

        internal static bool ValidResponse(byte[] frame, byte[] source, byte[] target)
        {
            if (frame.Length < 34 || !frame.Take(6).SequenceEqual(source) || !frame.Skip(6).Take(6).SequenceEqual(target) ||
                frame[12] != 0x99 || frame[13] != 0x74 || frame[18] != 1 || frame[19] != 2) return false;
            int length = frame[32] * 256 + frame[33];
            if (length != 1448 || frame.Length < 34 + length) return false;
            using (var md5 = MD5.Create()) return md5.ComputeHash(frame.Skip(18).Take(16 + length).ToArray()).Take(4).SequenceEqual(frame.Skip(14).Take(4));
        }

        internal static string Run(RescueAdapter adapter, string targetMac, bool restore)
        {
            if (adapter == null) throw new ArgumentException("请选择连接D31的电脑有线网卡。");
            byte[] source = adapter.Nic.GetPhysicalAddress().GetAddressBytes(), target = Mac(targetMac);
            string dll = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "Npcap", "wpcap.dll");
            if (!File.Exists(dll) || LoadLibraryEx(dll, IntPtr.Zero, 0x100 | 0x800) == IntPtr.Zero)
                throw new IOException("需要官方Npcap驱动。请从npcap.com安装后重试；若已安装，请检查管理员访问权限。");
            var error = new StringBuilder(256);
            IntPtr handle = pcap_open_live("\\Device\\NPF_" + adapter.Nic.Id, 2048, 0, 100, error);
            if (handle == IntPtr.Zero) throw new IOException("无法打开有线网卡：" + error);
            try
            {
                if (pcap_datalink(handle) != 1 || pcap_setnonblock(handle, 1, error) != 0) throw new IOException("网卡不支持以太网非阻塞查询。");
                var filter = new Bpf();
                string expression = "ether proto 0x9974 and ether src " + BitConverter.ToString(target).Replace('-', ':') +
                    " and ether dst " + BitConverter.ToString(source).Replace('-', ':');
                if (pcap_compile(handle, ref filter, expression, 1, 0xffffffff) != 0) throw new IOException("无法编译定向捕获规则。");
                try { if (pcap_setfilter(handle, ref filter) != 0) throw new IOException("无法应用定向捕获规则。"); }
                finally { pcap_freecode(ref filter); }
                // 每次操作先单播验证目标响应，再允许发送固定恢复命令。
                byte[] query = Frame(source, target, Session(), false);
                if (pcap_sendpacket(handle, query, query.Length) != 0) throw new IOException("设备查询发送失败。");
                var watch = Stopwatch.StartNew();
                bool found = false;
                while (watch.Elapsed.TotalSeconds < 8)
                {
                    IntPtr header, data;
                    int status = pcap_next_ex(handle, out header, out data);
                    if (status < 0) throw new IOException("网卡捕获失败。");
                    if (status == 0) { Thread.Sleep(20); continue; }
                    int size = Marshal.ReadInt32(header, 8);
                    if (size < 34 || size > 2048) continue;
                    byte[] packet = new byte[size]; Marshal.Copy(data, packet, 0, size);
                    if (ValidResponse(packet, source, target)) { found = true; break; }
                }
                if (!found) throw new IOException("目标未返回有效uptool设备信息。检查网卡、D31有线MAC及网线；不要求互联网连接。");
                if (!restore) return "uptool单播查询通过：目标返回有效设备信息，校验正确。";
                byte[] commandFrame = Frame(source, target, Session(), true);
                if (pcap_sendpacket(handle, commandFrame, commandFrame.Length) != 0) throw new IOException("恢复ADB命令发送失败。");
                return "已发送一次保留有效运行/持久端口的恢复命令，仅无有效配置时使用5555；旧uptool无命令输出回执，端口与ADB握手尚未确认。";
            }
            finally { pcap_close(handle); }
        }
    }
}
