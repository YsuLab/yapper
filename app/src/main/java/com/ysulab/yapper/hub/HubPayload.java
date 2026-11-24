package com.ysulab.yapper.hub;

import com.ysulab.yapper.sort.SortAction;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The {@code POST /api/captures} body for one capture: the raw transcript plus every task and Unsorted
 * item the sorter found. Item ids come from the capture id and the item's place in the saved plan, so a
 * resend after a crash or an offline wait never makes a duplicate on the hub. Pure Java.
 */
public final class HubPayload {

    private HubPayload() {}

    /** "20261124-213455-ab12" + plan index 3 → "20261124-213455-ab12-4". */
    public static String itemId(String captureId, int planIndex) {
        return captureId + "-" + (planIndex + 1);
    }

    public static boolean isHubItem(SortAction a) {
        return SortAction.TASK.equals(a.tool) || SortAction.UNSORTED.equals(a.tool);
    }

    public static JSONObject build(String captureId, ZonedDateTime capturedAt, String transcript,
            List<SortAction> plan) throws JSONException {
        JSONArray items = new JSONArray();
        for (int i = 0; i < plan.size(); i++) {
            SortAction a = plan.get(i);
            if (!isHubItem(a)) {
                continue;
            }
            boolean unsorted = SortAction.UNSORTED.equals(a.tool) || a.unsure;
            String text = a.title.isEmpty() ? a.quote : a.title;
            JSONObject it = new JSONObject()
                    .put("id", itemId(captureId, i))
                    .put("kind", unsorted ? "unsorted" : "task")
                    .put("text", text);
            if (!a.quote.isEmpty()) {
                it.put("quote", a.quote);
            }
            if (!a.remindAt.isEmpty()) {
                it.put("due", a.remindAt);
            }
            items.put(it);
        }
        return new JSONObject()
                .put("id", captureId)
                .put("capturedAt", capturedAt.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .put("transcript", transcript == null ? "" : transcript)
                .put("src", "voice")
                .put("items", items);
    }

    /** Ids of the hub items in a saved payload (for Undo). */
    public static java.util.List<String> itemIds(String payloadJson) throws JSONException {
        java.util.List<String> out = new java.util.ArrayList<>();
        JSONArray items = new JSONObject(payloadJson).optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            out.add(items.getJSONObject(i).getString("id"));
        }
        return out;
    }
}
