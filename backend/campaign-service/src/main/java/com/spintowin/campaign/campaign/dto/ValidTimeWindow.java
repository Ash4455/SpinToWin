package com.spintowin.campaign.campaign.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** {@code endAt} 必須晚於 {@code startAt}；錯誤掛在 {@code endAt} 欄位上。 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidTimeWindow.Validator.class)
public @interface ValidTimeWindow {

    String message() default "must be after startAt";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidTimeWindow, CampaignRequest> {

        @Override
        public boolean isValid(CampaignRequest request, ConstraintValidatorContext context) {
            if (request == null || request.startAt() == null || request.endAt() == null) {
                return true; // 缺值交給 @NotNull
            }
            if (request.endAt().isAfter(request.startAt())) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("endAt")
                    .addConstraintViolation();
            return false;
        }
    }
}
