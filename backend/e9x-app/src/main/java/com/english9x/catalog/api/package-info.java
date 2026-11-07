/**
 * Public surface of the {@code catalog} module: what an exam is (sections, the 12 interaction kinds, the 31 question types with their
 * JSON Schemas), how raw scores become scaled scores ({@link com.english9x.catalog.api.ScoreTables}) and who grades a writing or
 * speaking answer ({@link com.english9x.catalog.api.GradingPolicies}). Everything else in {@code catalog} is internal.
 */
@org.springframework.modulith.NamedInterface("api")
package com.english9x.catalog.api;
