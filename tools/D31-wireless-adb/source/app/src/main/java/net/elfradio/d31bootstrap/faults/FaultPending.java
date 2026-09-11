package net.elfradio.d31bootstrap.faults;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small metadata pages for a host-owned queue; never read ZIPs or source bytes here. */
final class FaultPending {
    static JSONObject read(File root, int limit, String after) throws Exception {
        if (limit < 1 || limit > 16) throw new IllegalArgumentException("INVALID_PENDING_LIMIT");
        if (after == null || !after.isEmpty() && !after.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("INVALID_EVENT_CURSOR");
        FaultArchive archive = new FaultArchive(root, null, false);
        List<String> ids = archive.ids(); JSONArray items = new JSONArray();
        int first = 0;
        while (first < ids.size() && ids.get(first).compareTo(after) <= 0) first++;
        String next = "";
        for (int i = first; i < ids.size() && i < first + limit; i++) {
            String id = ids.get(i); File event = archive.event(id);
            JSONObject item = new JSONObject().put("eventId", id).put("category", "UNKNOWN")
                    .put("phase", "INDEX_CORRUPT").put("captureState", "UNKNOWN")
                    .put("exportState", "UNKNOWN").put("archiveState", "UNKNOWN");
            try {
                JSONObject descriptor = FaultArchive.read(new File(event, "event.json"));
                FaultSources.Candidate candidate = FaultSources.Candidate.parse(descriptor.getJSONObject("source"));
                if (!id.equals(candidate.id())) throw new IOException("EVENT_ID_MISMATCH");
                JSONObject state = FaultArchive.read(new File(event, "state.json"));
                item.put("category", candidate.category)
                        .put("phase", token(state.optString("phase"), "PENDING|CAPTURING|RETRY_WAIT|CAPACITY_BLOCKED|AWAITING_POST|COMPLETE|PARTIAL"))
                        .put("captureState", token(state.optString("capture"), "COMPLETE|PARTIAL"));
                String exported = "NOT_EXPORTED";
                for (int number = 1; number <= FaultExports.MAX_EXPORTS; number++) {
                    File directory = new File(event, "exports/export-" + number); FaultArchive.checked(directory);
                    if (!directory.exists()) continue;
                    if (!"RECEIPT_RECORDED_UNVERIFIED".equals(exported)) exported = "INCOMPLETE_EXPORT";
                    File file = new File(directory, "receipt.json"); FaultArchive.checked(file);
                    if (!file.exists()) continue;
                    JSONObject receipt = FaultArchive.read(file);
                    if (id.equals(receipt.optString("eventId")) && "EXPORTED".equals(receipt.optString("state")))
                        exported = "RECEIPT_RECORDED_UNVERIFIED";
                }
                item.put("exportState", exported);
                File marker = new File(event, "archived.json"); FaultArchive.checked(marker);
                String archived = "NOT_ACKNOWLEDGED";
                if (marker.exists()) {
                    JSONObject ack = FaultArchive.read(marker);
                    archived = id.equals(ack.optString("eventId")) && "ARCHIVED".equals(ack.optString("state"))
                            ? "ACK_RECORDED_UNVERIFIED" : "INVALID_RECORD";
                }
                item.put("archiveState", archived);
            } catch (Exception damaged) { item.put("phase", "INDEX_CORRUPT"); }
            items.put(item); next = id;
        }
        JSONObject scan;
        try { scan = FaultArchive.read(new File(root, "scan.json")); }
        catch (Exception missing) { scan = new JSONObject(); }
        JSONObject capacity = new JSONObject().put("scope", "LAST_SCAN_NOT_LIVE")
                .put("state", token(scan.optString("state"), "CAPACITY_LIMIT|PARTIAL|FINISHED"));
        for (String key : new String[]{"capturedAtMs", "activeEvents", "archivedEvents", "retainedEvents", "retainedBytes",
                "maxActiveEvents", "maxRetainedEvents", "maxArchiveBytes", "eventIndexErrors", "exportHeadroomBytes",
                "collectingEvents", "awaitingArchiveEvents", "maxCollectingEvents"})
            capacity.put(key, Math.max(-1, scan.optLong(key, -1)));
        JSONArray reasons = new JSONArray(), recorded = scan.optJSONArray("admissionBlockedBy");
        for (String reason : new String[]{"ACTIVE_EVENT_LIMIT", "COLLECTING_EVENT_LIMIT", "RETAINED_EVENT_LIMIT", "ACTIVE_RESERVATION_LIMIT",
                "ARCHIVE_BYTE_LIMIT", "EXPORT_HEADROOM_LIMIT", "FREE_SPACE_RESERVE"}) {
            if (recorded == null) break;
            for (int i = 0; i < recorded.length(); i++) if (reason.equals(recorded.optString(i))) { reasons.put(reason); break; }
        }
        capacity.put("admissionBlockedBy", reasons);
        capacity.put("admissionPolicy", token(scan.optString("admissionPolicy"), "IN_FLIGHT_AND_RETAINED_BUDGETS|UNARCHIVED_EVENT_LIMIT"));
        capacity.put("automaticArchive", false).put("originalsDeleted", false)
                .put("continuationAction", "CAPACITY_LIMIT".equals(capacity.optString("state"))
                        ? "HOST_VERIFY_EXPORT_AND_ACK_OR_RETAINED_CAPACITY_REVIEW" : "NONE");
        JSONObject result = new JSONObject().put("schemaVersion", 1).put("kind", "FAULT_PENDING_INDEX")
                .put("events", items).put("total", ids.size()).put("hasMore", first + items.length() < ids.size())
                .put("nextAfter", next).put("capacity", capacity).put("verificationScope", "METADATA_ONLY")
                .put("selection", "ALL_RETAINED_HOST_SELECTS_PENDING").put("pagination", "LIVE_ID_ORDER_RESTART_FROM_EMPTY_EACH_SWEEP")
                .put("rawContentInSummary", false).put("uploadState", "LOCAL_ONLY");
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > 8000) throw new IOException("PENDING_SIZE_LIMIT");
        return result;
    }
    private static String token(String value, String allowed) { return value.matches(allowed) ? value : "UNKNOWN"; }
    private FaultPending() { }
}
