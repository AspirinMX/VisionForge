package app.pagefault.visionforge.common.api;

import java.util.List;

/**
 * Offset-based page payload used by administrative lists.
 */
public record PageResult<T>(List<T> items, long page, long size, long total) {

    public PageResult {
        items = List.copyOf(items);
    }

    public static <T> PageResult<T> of(List<T> items, long page, long size, long total) {
        return new PageResult<>(items, page, size, total);
    }
}
