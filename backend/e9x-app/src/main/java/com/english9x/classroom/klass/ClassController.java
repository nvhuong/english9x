package com.english9x.classroom.klass;

import com.english9x.classroom.klass.ClassDtos.AddClassMembersRequest;
import com.english9x.classroom.klass.ClassDtos.AddClassMembersResponse;
import com.english9x.classroom.klass.ClassDtos.AssignClassCoursesRequest;
import com.english9x.classroom.klass.ClassDtos.AssignClassCoursesResponse;
import com.english9x.classroom.klass.ClassDtos.ClassMemberView;
import com.english9x.classroom.klass.ClassDtos.ClassView;
import com.english9x.classroom.klass.ClassDtos.CreateClassRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** F45: classes (centre) and groups (enterprise): create, roster, courses for the whole class. */
@RestController
class ClassController {

    private final ClassService classes;

    ClassController(ClassService classes) {
        this.classes = classes;
    }

    @PostMapping("/api/orgs/{id}/classes")
    @Operation(summary = "Create a class (a centre: teacher required) or a group (an enterprise: no teacher); ORG_ADMIN")
    @ResponseStatus(HttpStatus.CREATED)
    ClassView create(@PathVariable UUID id, @Valid @RequestBody CreateClassRequest body) {
        return classes.create(id, body.name(), body.teacherId());
    }

    @GetMapping("/api/orgs/{id}/classes")
    List<ClassView> list(@PathVariable UUID id) {
        return classes.list(id);
    }

    @GetMapping("/api/orgs/{id}/classes/{cid}")
    ClassView get(@PathVariable UUID id, @PathVariable UUID cid) {
        return classes.get(id, cid);
    }

    @GetMapping("/api/orgs/{id}/classes/{cid}/members")
    @Operation(summary = "The roster; ORG_ADMIN or the class's teacher")
    List<ClassMemberView> members(@PathVariable UUID id, @PathVariable UUID cid) {
        return classes.members(id, cid);
    }

    @PostMapping("/api/orgs/{id}/classes/{cid}/members")
    @Operation(summary = "Add members of the organisation to the class; ORG_ADMIN or the class's teacher")
    AddClassMembersResponse addMembers(@PathVariable UUID id, @PathVariable UUID cid, @Valid @RequestBody AddClassMembersRequest body) {
        return classes.addMembers(id, cid, body.userIds());
    }

    @DeleteMapping("/api/orgs/{id}/classes/{cid}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMember(@PathVariable UUID id, @PathVariable UUID cid, @PathVariable UUID userId) {
        classes.removeMember(id, cid, userId);
    }

    @PostMapping("/api/orgs/{id}/classes/{cid}/courses")
    @Operation(summary = "Give the whole class the courses: one seat of the licence per member (the P5.3 seat path); ORG_ADMIN")
    AssignClassCoursesResponse assignCourses(@PathVariable UUID id, @PathVariable UUID cid, @Valid @RequestBody AssignClassCoursesRequest body) {
        return classes.assignCourses(id, cid, body.courseIds(), body.licenseId());
    }
}
