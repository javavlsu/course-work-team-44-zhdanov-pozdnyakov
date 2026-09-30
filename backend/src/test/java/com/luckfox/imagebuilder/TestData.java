package com.luckfox.imagebuilder;

import com.luckfox.imagebuilder.entity.Component;
import com.luckfox.imagebuilder.entity.ComponentType;
import com.luckfox.imagebuilder.entity.Image;
import com.luckfox.imagebuilder.entity.Role;
import com.luckfox.imagebuilder.entity.User;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Фабрики сущностей для модульных тестов. */
public final class TestData {

    private TestData() {
    }

    public static ComponentType type(long id, String name) {
        return new ComponentType(id, name);
    }

    public static Component component(long id, String name, long cost, Component... deps) {
        Component c = new Component();
        c.setId(id);
        c.setName(name);
        c.setCost(cost);
        c.setType(type(1, "library"));
        c.setDependencies(new LinkedHashSet<>(List.of(deps)));
        return c;
    }

    public static Image image(long id, String state) {
        Image i = new Image();
        i.setId(id);
        i.setName("image-" + id);
        i.setState(state);
        i.setCost(0L);
        i.setComponents(new LinkedHashSet<>());
        return i;
    }

    public static Role role(long id, String name) {
        Role r = new Role();
        r.setId(id);
        r.setRoleName(name);
        return r;
    }

    public static User user(long id, String email, Role... roles) {
        User u = new User();
        u.setId(id);
        u.setName("Имя");
        u.setEmail(email);
        u.setPassword("hash");
        u.setRoles(new HashSet<>(Set.of(roles)));
        return u;
    }
}
