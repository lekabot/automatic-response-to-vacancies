package ru.hhassistant.domain.model;

public record VacancyCandidate(
  String vacancyId,
  String title,
  String employer,
  String url,
  String salaryText,   // null is empty
  boolean hasTest,
  String areaName
) {

  public boolean matchesExclude(Iterable<String> excludeKeywords) {
    var titleLow = title.toLowerCase();
    var employerLow = employer != null ? employer.toLowerCase() : "";
    for (var kw : excludeKeywords) {
      var kwLow = kw.trim().toLowerCase();
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
