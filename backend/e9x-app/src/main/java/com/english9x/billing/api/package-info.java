/**
 * Public surface of the {@code billing} module: {@link com.english9x.billing.api.Entitlements}, the one gate that decides whether a person
 * may open course content or continue an attempt (spec section 5: "the single access rule"). The events live in {@code api.events}.
 */
@org.springframework.modulith.NamedInterface("api")
package com.english9x.billing.api;
