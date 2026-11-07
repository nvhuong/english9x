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
import java.nio.charset.StandardCharsets;

/** At most {@code value} UTF-8 bytes (a password must fit bcrypt's 72). */
@Documented
@Constraint(validatedBy = Utf8MaxBytes.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@interface Utf8MaxBytes {
    int value();

    String message() default "must be at most {value} bytes when encoded as UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<Utf8MaxBytes, String> {
        private int max;

        @Override
        public void initialize(Utf8MaxBytes a) {
            this.max = a.value();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext ctx) {
            return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
        }
    }
}
