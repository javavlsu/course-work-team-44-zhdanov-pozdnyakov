package com.luckfox.imagebuilder.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.function.Function;

/**
 * Страница списка: элементы + сведения для пагинатора на фронтенде.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageResponse<T> {
    public static final int MAX_SIZE = 100;

    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    /**
     * Сортировка из параметра запроса: price_asc / price_desc — по стоимости
     * (при равной цене — по умолчанию), иначе — сортировка по умолчанию.
     */
    public static Sort sort(String sort, Sort byDefault) {
        if ("price_asc".equals(sort)) return Sort.by(Sort.Direction.ASC, "cost").and(byDefault);
        if ("price_desc".equals(sort)) return Sort.by(Sort.Direction.DESC, "cost").and(byDefault);
        return byDefault;
    }

    /** Запрос страницы с защитой от отрицательного номера и слишком большого размера. */
    public static Pageable request(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE), sort);
    }
}
