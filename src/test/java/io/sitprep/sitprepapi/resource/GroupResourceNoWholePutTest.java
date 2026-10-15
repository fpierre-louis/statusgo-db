package io.sitprep.sitprepapi.resource;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** BE-01 step 2: PUT /groups/{id} (whole-group update) is gone; settings go through PATCH. */
class GroupResourceNoWholePutTest {

    @Test
    void noPutMappingOnTheBareGroupId() {
        for (Method m : GroupResource.class.getDeclaredMethods()) {
            PutMapping pm = m.getAnnotation(PutMapping.class);
            if (pm == null) continue;
            for (String path : pm.value()) {
                assertTrue(!path.equals("/{groupId}"), "whole-group PUT must not exist: " + m.getName());
            }
        }
    }
}
