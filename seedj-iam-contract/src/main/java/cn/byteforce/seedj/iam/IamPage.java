package cn.byteforce.seedj.iam;

import java.util.List;

/** Stable paged result; total is the unpaged count. */
public record IamPage<T>(List<T> items, int totalResults, int startIndex, int itemsPerPage) {

    public IamPage {
        items = items == null ? List.of() : List.copyOf(items);
        totalResults = Math.max(0, totalResults);
        startIndex = Math.max(1, startIndex);
        itemsPerPage = items.size();
    }

    public static <T> IamPage<T> of(List<T> all, int startIndex, int count) {
        int from = Math.min(Math.max(0, startIndex - 1), all.size());
        int to = Math.min(all.size(), from + Math.max(0, count));
        return new IamPage<>(all.subList(from, to), all.size(), startIndex, to - from);
    }
}
