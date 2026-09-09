package net.elfradio.d31bootstrap;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;
import java.net.InetAddress;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class RescueHttpServer extends NanoHTTPD {
    interface Status { String get(); }
    private final RescueJobs jobs;
    private final Status status;

    RescueHttpServer(int port, RescueJobs jobs, Status status) {
        super("0.0.0.0", port);
        this.jobs = jobs;
        this.status = status;
        // Bound the socket workers, so a stalled peer cannot create unlimited threads.
        setAsyncRunner(new AsyncRunner() {
            private final java.util.concurrent.ThreadPoolExecutor pool =
                    new java.util.concurrent.ThreadPoolExecutor(4, 4, 0,
                    java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(8));
            private final java.util.Set<ClientHandler> clients =
                    java.util.Collections.synchronizedSet(new java.util.HashSet<>());
            public void exec(ClientHandler handler) {
                clients.add(handler);
                try { pool.execute(handler); }
                catch (java.util.concurrent.RejectedExecutionException busy) { handler.close(); clients.remove(handler); }
            }
            public void closed(ClientHandler handler) { clients.remove(handler); }
            public void closeAll() {
                synchronized (clients) { for (ClientHandler c : clients) c.close(); clients.clear(); }
                pool.shutdownNow();
            }
        });
    }

    @Override public Response serve(IHTTPSession session) {
        try {
            InetAddress peer = InetAddress.getByName(session.getRemoteIpAddress());
            if (!peer.isSiteLocalAddress() && !peer.isLoopbackAddress())
                return response(Response.Status.FORBIDDEN, "仅开放局域网");
            String path = session.getUri();
            if (session.getMethod() == Method.GET && "/health".equals(path))
                return json(Response.Status.OK, new JSONObject().put("version", BuildConfig.VERSION_NAME)
                        .put("version_code", BuildConfig.VERSION_CODE)
                        .put("service", "d31-root-rescue").put("busy", jobs.isBusy())
                        .put("uid", android.system.Os.getuid())
                        .put("uptime_ms", android.os.SystemClock.elapsedRealtime()));
            if (session.getMethod() == Method.GET && "/".equals(path))
                return response(Response.Status.OK, status.get());
            if (session.getMethod() == Method.GET && path.startsWith("/jobs/")) {
                JSONObject result = jobs.get(path.substring(6));
                return result == null ? response(Response.Status.NOT_FOUND, "任务不存在")
                        : json(Response.Status.OK, result);
            }
            if (session.getMethod() != Method.POST || !"/exec".equals(path))
                return response(Response.Status.NOT_FOUND, "使用 POST /exec 或 GET /jobs/任务号");
            // No password or pairing. JSON-only POST also avoids accidental browser form execution.
            String contentType = session.getHeaders().get("content-type");
            if (contentType == null || !contentType.split(";")[0].trim().equalsIgnoreCase("application/json")
                    || session.getHeaders().containsKey("transfer-encoding")
                    || session.getHeaders().containsKey("origin"))
                return response(Response.Status.BAD_REQUEST, "需要无Origin的application/json请求");
            long length = Long.parseLong(session.getHeaders().get("content-length"));
            if (length < 1 || length > 16384) return response(Response.Status.BAD_REQUEST, "请求大小超限");
            // 命令正文有严格上限，直接读取，避免独立进程依赖不存在的临时目录。
            byte[] body = new byte[(int) length];
            InputStream input = session.getInputStream();
            int offset = 0;
            while (offset < body.length) {
                int count = input.read(body, offset, body.length - offset);
                if (count < 0) throw new EOFException("请求正文不完整");
                offset += count;
            }
            JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
            JSONObject result = jobs.submit(request.getString("id"), request.getString("command"),
                    request.optInt("timeout", 30));
            return json(Response.Status.ACCEPTED, result);
        } catch (IllegalStateException busy) {
            return response(Response.Status.CONFLICT, busy.getMessage());
        } catch (Exception error) {
            return response(Response.Status.BAD_REQUEST, "请求失败：" + error.getMessage());
        }
    }

    private static Response json(Response.Status code, JSONObject body) {
        Response result = newFixedLengthResponse(code, "application/json; charset=utf-8", body.toString());
        result.addHeader("Cache-Control", "no-store"); return result;
    }
    private static Response response(Response.Status code, String body) {
        Response result = newFixedLengthResponse(code, "text/plain; charset=utf-8", body);
        result.addHeader("Cache-Control", "no-store"); return result;
    }
}
