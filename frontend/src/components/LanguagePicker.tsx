import { LANGUAGES, isLang, useI18n } from "../lib/i18n";
import styles from "./LanguagePicker.module.css";

/** The interface language list (English, Russian). The choice is remembered on this device. */
export function LanguagePicker({ tone = "paper" }: { tone?: "paper" | "night" }) {
  const { lang, setLang, t } = useI18n();
  return (
    <span className={`${styles.picker} ${tone === "night" ? styles.night : ""}`}>
      <span aria-hidden="true">🌐</span>
      <select aria-label={t("common.language")} value={lang} onChange={(e) => isLang(e.target.value) && setLang(e.target.value)}>
        {LANGUAGES.map((l) => (
          <option key={l.code} value={l.code} lang={l.code}>
            {l.name}
          </option>
        ))}
      </select>
    </span>
  );
}
