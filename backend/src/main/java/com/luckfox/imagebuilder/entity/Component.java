package com.luckfox.imagebuilder.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Set;

@Entity
// Индексы под сортировку каталога по названию и фильтр по типу с сортировкой по цене (DEF-23).
@Table(name = "Component", indexes = {
        @Index(name = "idx_component_name", columnList = "name, id"),
        @Index(name = "idx_component_type_cost", columnList = "type, cost")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Component {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne
    @JoinColumn(name = "type", nullable = false)
    private ComponentType type;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Long cost;

    // Оптимистичная блокировка: защита от потерянных обновлений при
    // одновременном редактировании компонента администраторами.
    @Version
    @Column(name = "version")
    private Long version;

    // Self-referencing many-to-many for component dependencies
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "dependency",
        joinColumns = @JoinColumn(name = "parent_id"),
        inverseJoinColumns = @JoinColumn(name = "dependent_id")
    )
    private Set<Component> dependencies;
}
