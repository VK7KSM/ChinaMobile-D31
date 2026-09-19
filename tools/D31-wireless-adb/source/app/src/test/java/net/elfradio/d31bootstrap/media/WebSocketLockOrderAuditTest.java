package net.elfradio.d31bootstrap.media;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 持有对象锁期间不得调用 WebSocket 的 close。
 *
 * 起因是网关 2026-09-19 的一次失联：两条线程按相反顺序各持一把锁互等——
 * 一条持会话锁去调 websocket.close()（要拿 WebSocket 自己的锁），
 * 另一条持 WebSocket 锁从 onClose 回调进来要会话锁。结果不是报错而是整条会话卡住，
 * 上报线程跟着停了近八分钟，进程还活着、last_error 是空的，极难查。
 * D31 的 AdbSessions 与 DesktopSession 当时是同一形态。
 *
 * 死锁普通单测抓不到，真机上也未必每次触发，所以这里直接扫编译产物的字节码：
 * 线性走一遍方法体，monitorenter 加一、monitorexit 减一（异常处理器里那次减法会让计数
 * 变负，夹到 0），只要在计数大于零时出现对 WebSocket 的 close 调用就判失败。
 * 为何不连 send 一起查，见下面 forbidden() 上的说明。
 *
 * 只看方法自己的字节码是不够的：持锁的方法可能隔着一两跳才走到 close
 * （D31 的 checkReady 就是 synchronized 里调 fail、fail 调 stop、stop 才关）。
 * 所以先按调用图算出所有能传递地走到 close 的方法，再查有没有在持锁时调用它们。
 * 方法整体声明成 synchronized 的同样算持锁。
 */
public class WebSocketLockOrderAuditTest {
    private static final String PACKAGE_ROOT = "net/elfradio/d31bootstrap";
    private static final int ACC_SYNCHRONIZED = 0x0020;
    private static final int MONITORENTER = 194, MONITOREXIT = 195;

    private static final class Pool {
        String[] utf8;
        int[] classNameIndex, refClassIndex, refNatIndex, natNameIndex;
        String refClass(int index) {
            int c = refClassIndex[index];
            return c == 0 ? "" : utf8[classNameIndex[c]];
        }
        String refName(int index) {
            int n = refNatIndex[index];
            return n == 0 ? "" : utf8[natNameIndex[n]];
        }
    }

    private static final class Violation {
        final String owner, method, target;
        Violation(String owner, String method, String target) {
            this.owner = owner; this.method = method; this.target = target;
        }
        @Override public String toString() { return owner + "." + method + " → " + target; }
    }

    private static Pool pool(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        Pool p = new Pool();
        p.utf8 = new String[count];
        p.classNameIndex = new int[count];
        p.refClassIndex = new int[count];
        p.refNatIndex = new int[count];
        p.natNameIndex = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: p.utf8[i] = in.readUTF(); break;
                case 7: case 8: case 16: case 19: case 20: p.classNameIndex[i] = in.readUnsignedShort(); break;
                case 15: in.skipBytes(3); break;
                case 9: case 10: case 11:
                    p.refClassIndex[i] = in.readUnsignedShort(); p.refNatIndex[i] = in.readUnsignedShort(); break;
                case 12:
                    p.natNameIndex[i] = in.readUnsignedShort(); in.skipBytes(2); break;
                case 17: case 18: in.skipBytes(4); break;
                case 3: case 4: in.skipBytes(4); break;
                case 5: case 6: in.skipBytes(8); i++; break;
                default: throw new IOException("未知常量池标签 " + tag);
            }
        }
        return p;
    }

    /** 指令长度表；只需要走到下一条指令，不需要理解语义。 */
    private static int length(byte[] code, int at) {
        int op = code[at] & 0xff;
        switch (op) {
            case 196: { // wide
                int next = code[at + 1] & 0xff;
                return next == 132 ? 6 : 4; // wide iinc 否则 wide <load/store>
            }
            case 170: { // tableswitch
                int pad = (4 - ((at + 1) % 4)) % 4;
                int base = at + 1 + pad;
                int low = read32(code, base + 4), high = read32(code, base + 8);
                return (base + 12 + (high - low + 1) * 4) - at;
            }
            case 171: { // lookupswitch
                int pad = (4 - ((at + 1) % 4)) % 4;
                int base = at + 1 + pad;
                int pairs = read32(code, base + 4);
                return (base + 8 + pairs * 8) - at;
            }
            default: return 1 + OPERANDS[op];
        }
    }

    private static int read32(byte[] code, int at) {
        return ((code[at] & 0xff) << 24) | ((code[at + 1] & 0xff) << 16)
                | ((code[at + 2] & 0xff) << 8) | (code[at + 3] & 0xff);
    }

    private static final int[] OPERANDS = operands();

    private static int[] operands() {
        int[] n = new int[256];
        // 默认 0；下面按操作数字节数登记，switch 与 wide 在 length() 里单独处理。
        int[] one = {16, 18, 21, 22, 23, 24, 25, 54, 55, 56, 57, 58, 169, 188};
        int[] two = {17, 19, 20, 132, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164,
                165, 166, 167, 168, 178, 179, 180, 181, 182, 183, 184, 187, 189, 192, 193, 198, 199};
        int[] three = {197};
        int[] four = {185, 186, 200, 201};
        for (int op : one) n[op] = 1;
        for (int op : two) n[op] = 2;
        for (int op : three) n[op] = 3;
        for (int op : four) n[op] = 4;
        return n;
    }

    /**
     * 只查 close，不查 send，这个范围是有意划的。
     *
     * close 会走 Java-WebSocket 的关闭锁，而读线程投递 onClose 时也在同一把锁里，
     * 两边锁序一反就是永久死锁——网关那次就是这么来的，属于已证实的形态。
     * send 走的是发送队列，持锁期间发送最坏是串行化或阻塞一阵，会恢复，不是死锁。
     * 把 send 也判失败会逼着去改正在正常工作的照片/警报通路，只凭一条理论风险不值得，
     * 而且会让这道审计变成「狼来了」。已知 PhotoAlarmSocket.send 属于此类，另行评估。
     */
    private static boolean forbidden(String owner, String name) {
        return owner.contains("WebSocket") && "close".equals(name);
    }

    /** 一次方法体扫描的结果：它直接调了谁、以及持锁时调了谁。 */
    private static final class Body {
        final String owner, name;
        final List<String> calls = new ArrayList<>(), lockedCalls = new ArrayList<>();
        boolean closesDirectly;
        Body(String owner, String name) { this.owner = owner; this.name = name; }
        String key() { return owner + "#" + name; }
    }

    private static Body scanMethod(String className, String methodName, int access, byte[] code, Pool pool) {
        Body body = new Body(className, methodName);
        int depth = (access & ACC_SYNCHRONIZED) != 0 ? 1 : 0;
        for (int at = 0; at < code.length; at += length(code, at)) {
            int op = code[at] & 0xff;
            if (op == MONITORENTER) { depth++; continue; }
            if (op == MONITOREXIT) { depth = Math.max(0, depth - 1); continue; }
            // 只跟真正的调用指令。invokedynamic（186）刻意排除：外层方法只是**创建** lambda，
            // 真正执行发生在执行器或回调线程上，那时早已不持锁；当成调用边会造成大批误报。
            if (op != 182 && op != 183 && op != 184 && op != 185) continue;
            int index = ((code[at + 1] & 0xff) << 8) | (code[at + 2] & 0xff);
            String owner = pool.refClass(index), name = pool.refName(index);
            if (forbidden(owner, name)) {
                body.closesDirectly = true;
                if (depth > 0) body.lockedCalls.add(owner + "." + name);
                continue;
            }
            // 只跟本项目自己的方法；库内部的调用图不在审计范围内。
            if (!owner.startsWith(PACKAGE_ROOT)) continue;
            String callee = owner.replace('/', '.') + "#" + name;
            body.calls.add(callee);
            if (depth > 0) body.lockedCalls.add(callee);
        }
        return body;
    }

    private static void scanClass(String name, byte[] bytes, List<Body> bodies) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) return;
        in.readUnsignedShort(); in.readUnsignedShort();
        Pool pool = pool(in);
        in.readUnsignedShort(); in.readUnsignedShort(); in.readUnsignedShort();
        in.skipBytes(in.readUnsignedShort() * 2); // interfaces
        for (int kind = 0; kind < 2; kind++) { // fields 然后 methods
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                int access = in.readUnsignedShort();
                String member = pool.utf8[in.readUnsignedShort()];
                in.readUnsignedShort(); // descriptor
                int attributes = in.readUnsignedShort();
                for (int a = 0; a < attributes; a++) {
                    String attribute = pool.utf8[in.readUnsignedShort()];
                    int size = in.readInt();
                    if (kind == 0 || !"Code".equals(attribute)) { in.skipBytes(size); continue; }
                    in.readUnsignedShort(); in.readUnsignedShort();
                    byte[] code = new byte[in.readInt()];
                    in.readFully(code);
                    int exceptions = in.readUnsignedShort();
                    in.skipBytes(exceptions * 8);
                    int inner = in.readUnsignedShort();
                    for (int k = 0; k < inner; k++) { in.readUnsignedShort(); in.skipBytes(in.readInt()); }
                    bodies.add(scanMethod(name, member, access, code, pool));
                }
            }
        }
    }

    private static void collect(File directory, File root, List<File> found) {
        File[] entries = directory.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) collect(entry, root, found);
            else if (entry.getName().endsWith(".class")) found.add(entry);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    /** 能（传递地）走到 WebSocket close 的方法全集。 */
    private static java.util.Set<String> closers(List<Body> bodies) {
        java.util.Map<String, Body> byKey = new java.util.HashMap<>();
        for (Body b : bodies) byKey.put(b.key(), b);
        java.util.Set<String> reaching = new java.util.HashSet<>();
        for (Body b : bodies) if (b.closesDirectly) reaching.add(b.key());
        // 反复传播直到不再增长；方法数是几千量级，够用。
        for (boolean grew = true; grew; ) {
            grew = false;
            for (Body b : bodies) {
                if (reaching.contains(b.key())) continue;
                for (String callee : b.calls)
                    if (reaching.contains(callee)) { reaching.add(b.key()); grew = true; break; }
            }
        }
        return reaching;
    }

    @Test public void noWebSocketCloseReachableWhileHoldingALock() throws Exception {
        List<Body> bodies = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            File root = new File(entry);
            // 只审生产产物；测试里的假客户端不上设备。
            if (root.getAbsolutePath().contains("UnitTest")) continue;
            if (root.isDirectory()) {
                if (!new File(root, PACKAGE_ROOT).isDirectory()) continue;
                List<File> classes = new ArrayList<>();
                collect(new File(root, PACKAGE_ROOT), root, classes);
                for (File classFile : classes) {
                    String path = classFile.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
                    scanClass(path.replace(File.separatorChar, '.').replace(".class", ""),
                            Files.readAllBytes(classFile.toPath()), bodies);
                }
            } else if (root.isFile() && root.getName().endsWith(".jar")) {
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(root)) {
                    for (java.util.Enumeration<java.util.jar.JarEntry> it = jar.entries(); it.hasMoreElements(); ) {
                        java.util.jar.JarEntry item = it.nextElement();
                        String path = item.getName();
                        if (!path.startsWith(PACKAGE_ROOT + "/") || !path.endsWith(".class")) continue;
                        try (InputStream in = jar.getInputStream(item)) {
                            scanClass(path.replace('/', '.').replace(".class", ""), readAll(in), bodies);
                        }
                    }
                }
            }
        }

        java.util.Set<String> reaching = closers(bodies);
        List<Violation> found = new ArrayList<>();
        for (Body b : bodies)
            for (String callee : b.lockedCalls)
                if (!callee.contains("#") || reaching.contains(callee))
                    found.add(new Violation(b.owner, b.name, callee.replace('#', '.')));

        // 扫不到东西说明这道防线没生效，那比缺陷本身更糟：它会一直是绿的。
        assertFalse("没有扫到任何方法，审计没有真正执行", bodies.isEmpty());
        assertFalse("没有找到任何能关闭 WebSocket 的方法，调用图多半没建起来", reaching.isEmpty());
        assertEquals("以下方法在持锁期间（直接或经若干跳）走到了 WebSocket 的 close，"
                + "与读线程 onClose 回调构成反向锁序，会让整条会话连同上报一起卡死：" + found,
                java.util.Collections.emptyList(), found);
    }
}
