package ru.hhassistant.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CoverLetterRendererTest {

    @Test
    void render_replacesTitle() {
        String result = CoverLetterRenderer.render("Вакансия {title}", "Java Dev", "ACME");
        assertThat(result).isEqualTo("Вакансия Java Dev");
    }

    @Test
    void render_replacesEmployer() {
        String result = CoverLetterRenderer.render("Компания {employer}", "Java Dev", "ACME");
        assertThat(result).isEqualTo("Компания ACME");
    }

    @Test
    void render_replacesBothPlaceholders() {
        String result = CoverLetterRenderer.render(
            "Вакансия {title} в {employer}. Привет!", "Senior Java", "Google");
        assertThat(result).isEqualTo("Вакансия Senior Java в Google. Привет!");
    }

    @Test
    void render_unknownPlaceholder_leftAsIs() {
        String result = CoverLetterRenderer.render("{salary} зарплата", "Dev", "Co");
        assertThat(result).isEqualTo("{salary} зарплата");
    }

    @Test
    void render_nullTemplate_returnsEmpty() {
        assertThat(CoverLetterRenderer.render(null, "Dev", "Co")).isEmpty();
    }

    @Test
    void render_blankTemplate_returnsEmpty() {
        assertThat(CoverLetterRenderer.render("   ", "Dev", "Co")).isEmpty();
    }

    @Test
    void render_escapedNewline_convertedToActualNewline() {
        String result = CoverLetterRenderer.render("Привет!\\nС уважением.", "Dev", "Co");
        assertThat(result).contains("\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Привет {title}", "{employer} ждёт", "{title} в {employer}"})
    void validate_validTemplates_returnsNull(String template) {
        assertThat(CoverLetterRenderer.validate(template)).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "'{title', Непарные",
        "'{title}}', Лишняя",
    })
    void validate_invalidTemplates_returnsErrorMessage(String template, String expectedFragment) {
        String error = CoverLetterRenderer.validate(template);
        assertThat(error).isNotNull().contains(expectedFragment);
    }

    @Test
    void validate_emptyTemplate_returnsNull() {
        assertThat(CoverLetterRenderer.validate("")).isNull();
        assertThat(CoverLetterRenderer.validate(null)).isNull();
    }
}
