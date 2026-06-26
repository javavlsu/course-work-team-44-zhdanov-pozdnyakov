package com.luckfox.imagebuilder.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Set;

@Entity
@Table(name = "Component")
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
