package ru.hhassistant.domain.model;

/**
 * Вакансия, полученная из hh.ru API или HTML-парсера.
 * Иммутабельный value-object; создаётся инфраструктурным слоем.
 */
public record VacancyCandidate(
    String vacancyId,
    String title,
    String employer,
    String url,
    String salaryText,   // null = зарплата не указана
    boolean hasTest,
    String areaName
) {

    /** Проверяет, совпадает ли название вакансии или работодателя с любым из исключающих слов. */
    public boolean matchesExclude(Iterable<String> excludeKeywords) {
        String titleLow = title.toLowerCase();
        String employerLow = employer != null ? employer.toLowerCase() : "";
        for (String kw : excludeKeywords) {
            String kwLow = kw.trim().toLowerCase();
            if (!kwLow.isEmpty() && (titleLow.contains(kwLow) || employerLow.contains(kwLow))) {
                return true;
            }
        }
        return false;
    }

    public String displaySalary() {
        return salaryText != null && !salaryText.isBlank() ? salaryText : "з/п не указана";
    }
}
