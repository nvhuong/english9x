package com.english9x.identity.internal;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A new password has at least {@code e9x.auth.password-min-length} characters (read from configuration, not hard-coded here). */
@Documented
@Constraint(validatedBy = PasswordMinLength.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@interface PasswordMinLength {

    String message() default "is shorter than the minimum password length";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Created by Spring's constraint validator factory, which injects the configuration. */
    class Validator implements ConstraintValidator<PasswordMinLength, String> {
        private final int min;

        public Validator(AuthProperties props) {
            this.min = props.passwordMinLength();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext ctx) {
            return value == null || value.length() >= min;
        }
    }
}
