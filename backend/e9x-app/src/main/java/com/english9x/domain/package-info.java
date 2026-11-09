/**
 * Marks the e9x-domain jar for Spring Modulith. {@code com.english9x.domain.*} is the framework-free business logic (scoring, grading
 * policy, billing rules, exam lifecycle, ...) that every Spring module may call (Global Constraints). Its package sits under the
 * application's base package, so Modulith would otherwise treat it as a module whose sub-packages are internal and refuse every use of
 * it. This file is the only thing the application contributes to that package; it is an OPEN module: every type is exposed.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN, displayName = "Domain (e9x-domain)")
package com.english9x.domain;
