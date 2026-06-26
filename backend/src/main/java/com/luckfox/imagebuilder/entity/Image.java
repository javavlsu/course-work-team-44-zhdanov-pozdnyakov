package com.luckfox.imagebuilder.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Set;

@Entity
@Table(name = "Image")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Image {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // name/distro/board/artifact/log добавлены к исходной схеме (id,description,cost,state)
    // через ddl-auto=update — неразрушающее расширение таблицы.
    @Column
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column
    private String distro;

    @Column
    private String board;

    @Column(nullable = false)
    private Long cost;

    @Column(nullable = false)
    private String state; // CREATED, QUEUED, BUILDING, READY, FAILED

    @Column(name = "artifact_path")
    private String artifactPath;

    @Column(name = "build_log", columnDefinition = "TEXT")
    private String buildLog;

    // Оптимистичная блокировка от потерянных обновлений.
    @Version
    @Column(name = "version")
    private Long version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "image_component_dependency",
        joinColumns = @JoinColumn(name = "image_id"),
        inverseJoinColumns = @JoinColumn(name = "component_id")
    )
    private Set<Component> components;
}
