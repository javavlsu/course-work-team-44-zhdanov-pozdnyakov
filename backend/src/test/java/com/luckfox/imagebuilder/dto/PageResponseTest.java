package com.luckfox.imagebuilder.dto;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    private final Sort byName = Sort.by("name", "id");

    @Test
    void requestClampsNegativePageAndSize() {
        Pageable p = PageResponse.request(-3, 0, byName);

        assertThat(p.getPageNumber()).isZero();
        assertThat(p.getPageSize()).isEqualTo(1);
    }

    @Test
    void requestLimitsPageSize() {
        assertThat(PageResponse.request(2, 5000, byName).getPageSize()).isEqualTo(PageResponse.MAX_SIZE);
        assertThat(PageResponse.request(2, 30, byName).getPageSize()).isEqualTo(30);
    }

    @Test
    void priceSortPutsCostFirstAndKeepsDefaultAsTieBreaker() {
        Sort asc = PageResponse.sort("price_asc", byName);
        Sort desc = PageResponse.sort("price_desc", byName);

        assertThat(asc.toList()).containsExactly(Sort.Order.asc("cost"), Sort.Order.asc("name"), Sort.Order.asc("id"));
        assertThat(desc.toList().get(0)).isEqualTo(Sort.Order.desc("cost"));
    }

    @Test
    void unknownSortFallsBackToDefault() {
        assertThat(PageResponse.sort("drop table", byName)).isEqualTo(byName);
        assertThat(PageResponse.sort(null, byName)).isEqualTo(byName);
    }

    @Test
    void fromMapsContentAndPagingInfo() {
        PageImpl<Integer> page = new PageImpl<>(List.of(1, 2, 3), PageRequest.of(1, 3), 10);

        PageResponse<String> res = PageResponse.from(page, i -> "#" + i);

        assertThat(res.getContent()).containsExactly("#1", "#2", "#3");
        assertThat(res.getPage()).isEqualTo(1);
        assertThat(res.getSize()).isEqualTo(3);
        assertThat(res.getTotalElements()).isEqualTo(10);
        assertThat(res.getTotalPages()).isEqualTo(4);
    }
}
