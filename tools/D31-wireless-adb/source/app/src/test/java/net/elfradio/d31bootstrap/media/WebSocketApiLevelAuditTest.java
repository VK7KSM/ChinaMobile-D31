package net.elfradio.d31bootstrap.media;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLParameters;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 任何 WebSocketClient 子类都必须自己重写 onSetSSLParameters。
 *
 * 起因是一次真机崩溃：Java-WebSocket 的默认实现会调 SSLParameters.setEndpointIdentificationAlgorithm，
 * 那是 API 24 才有的方法，D31 是 API 23，于是抛 NoSuchMethodError 直接把整个应用进程打死，
 * 界面上只看到「elfRemote 已停止运行」，完全看不出和 TLS 有关。
 *
 * 这类缺陷普通单测抓不到：桌面 JVM 上那个方法是存在的，只有真机才炸。所以改成扫编译产物做结构检查——
 * 新加一个 WebSocket 客户端却忘了这道适配时，在这里就会失败，而不是等装到设备上才发现。
 */
public class WebSocketApiLevelAuditTest {
    private static final String WEBSOCKET_CLIENT = "org/java_websocket/client/WebSocketClient";
    private static final String PACKAGE_ROOT = "net/elfradio/d31bootstrap";

    /** 直接从 class 文件读父类名，不加载类：这些类的签名里带安卓接口，加载会连锁失败。 */
    private static String superName(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) return null;
        in.readUnsignedShort(); in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNames = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: utf8[i] = in.readUTF(); break;
                case 7: case 8: case 16: case 19: case 20: classNames[i] = in.readUnsignedShort(); break;
                case 15: in.skipBytes(3); break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: in.skipBytes(4); break;
                case 5: case 6: in.skipBytes(8); i++; break;
                default: return null;
            }
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int superIndex = in.readUnsignedShort();
        return superIndex == 0 ? null : utf8[classNames[superIndex]];
    }

    private static void collect(File directory, File root, List<File> found) {
        File[] entries = directory.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) collect(entry, root, found);
            else if (entry.getName().endsWith(".class")) found.add(entry);
        }
    }

    private static String binaryName(File classFile, File root) {
        String path = classFile.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
        return path.substring(0, path.length() - ".class".length()).replace(File.separatorChar, '.');
    }

    private void audit(String name, byte[] bytes, List<String> subclasses, List<String> missing) throws Exception {
        if (!WEBSOCKET_CLIENT.equals(superName(bytes))) return;
        subclasses.add(name);
        Class<?> type = Class.forName(name, false, getClass().getClassLoader());
        try { type.getDeclaredMethod("onSetSSLParameters", SSLParameters.class); }
        catch (NoSuchMethodException absent) { missing.add(name); }
    }

    @Test public void everyWebSocketClientSubclassOverridesOnSetSSLParameters() throws Exception {
        List<String> subclasses = new ArrayList<>(), missing = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            File root = new File(entry);
            // 只审生产产物：测试里的假客户端接的是内存流，永远不上设备，不该被这条规则约束。
            if (root.getAbsolutePath().contains("UnitTest")) continue;
            if (root.isDirectory()) {
                if (!new File(root, PACKAGE_ROOT).isDirectory()) continue;
                List<File> classes = new ArrayList<>();
                collect(new File(root, PACKAGE_ROOT), root, classes);
                for (File classFile : classes)
                    audit(binaryName(classFile, root), Files.readAllBytes(classFile.toPath()), subclasses, missing);
            } else if (root.isFile() && root.getName().endsWith(".jar")) {
                // 生产类在单测类路径上是打成 jar 的，不扫 jar 就等于什么都没审。
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(root)) {
                    for (java.util.Enumeration<java.util.jar.JarEntry> it = jar.entries(); it.hasMoreElements(); ) {
                        java.util.jar.JarEntry item = it.nextElement();
                        String path = item.getName();
                        if (!path.startsWith(PACKAGE_ROOT + "/") || !path.endsWith(".class")) continue;
                        byte[] bytes;
                        try (java.io.InputStream in = jar.getInputStream(item)) {
                            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                            byte[] buffer = new byte[16384];
                            for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
                            bytes = out.toByteArray();
                        }
                        audit(path.substring(0, path.length() - ".class".length()).replace('/', '.'),
                                bytes, subclasses, missing);
                    }
                }
            }
        }

        // 扫不到东西说明这道防线其实没生效，那比缺陷本身更糟：它会一直是绿的。
        assertFalse("没有扫到任何 WebSocketClient 子类，审计没有真正执行", subclasses.isEmpty());
        assertEquals("这些 WebSocketClient 子类没有重写 onSetSSLParameters，"
                + "在 API 23 的 D31 上建立 TLS 连接时会抛 NoSuchMethodError 打死整个进程：" + missing,
                java.util.Collections.emptyList(), missing);
    }
}
