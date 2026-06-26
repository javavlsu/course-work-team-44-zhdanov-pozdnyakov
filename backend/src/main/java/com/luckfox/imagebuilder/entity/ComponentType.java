package com.luckfox.imagebuilder.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "component_type")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ComponentType {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "Type", nullable = false)
    private String type;
}
