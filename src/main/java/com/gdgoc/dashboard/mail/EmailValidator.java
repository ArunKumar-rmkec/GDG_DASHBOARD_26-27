package com.gdgoc.dashboard.mail;

import com.gdgoc.dashboard.common.BadRequestException;

import java.util.List;
import java.util.regex.Pattern;

public final class EmailValidator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    private EmailValidator() {}

    public static boolean isValid(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email.trim()).matches();
    }

    public static void validate(String email, String fieldName) {
        if (!isValid(email)) {
            throw new BadRequestException("Invalid " + fieldName + " email format: '" + email + "'");
        }
    }

    public static void validateAll(List<String> emails, String fieldName) {
        if (emails != null) {
            for (String email : emails) {
                validate(email, fieldName);
            }
        }
    }
}
