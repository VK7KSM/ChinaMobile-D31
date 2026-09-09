package net.elfradio.d31system;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

final class LocalRootChannel {
    private LocalRootChannel() { }
    static SystemActions.ActionResult execute(String label, String command) {
        byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 32768 || command.indexOf('\0') >= 0)
            return new SystemActions.ActionResult(label + "：命令长度或内容无效", false);
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress("/dev/socket/d31-system-actions", LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(9000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(bytes.length); output.write(bytes); output.flush();
            DataInputStream input = new DataInputStream(socket.getInputStream());
            int exit = input.readInt(), length = input.readInt();
            if (length < 0 || length > 65536) throw new java.io.IOException("响应长度无效");
            byte[] result = new byte[length]; input.readFully(result);
            return new SystemActions.ActionResult(label + "\n" + new String(result, StandardCharsets.UTF_8)
                    + "\n退出码=" + exit, exit == 0);
        } catch (Exception error) {
            return new SystemActions.ActionResult(label + "：" + error, false);
        }
    }
}
