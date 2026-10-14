package tn.hydra.common;

import org.springframework.data.domain.Page;

import java.util.List;

/** Generic paginated envelope returned by list/search endpoints. */
public record PageResponse<T>(List<T> items, long total, int page, int size) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getTotalElements(),
                page.getNumber(), page.getSize());
    }
}