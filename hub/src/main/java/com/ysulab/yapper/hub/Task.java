package com.ysulab.yapper.hub;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** One task as the API returns it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Task(
        String id,
        String text,
        JsonNode ink,
        String status,
        String doneDay,
        JsonNode seam,
        long order,
        String src,
        String due,
        String quote,
        String captureId,
        String createdAt,
        String updatedAt) {

    public static final String OPEN = "open";
    public static final String DONE = "done";
    public static final String UNSORTED = "unsorted";
}
