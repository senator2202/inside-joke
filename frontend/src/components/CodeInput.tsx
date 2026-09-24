import { useI18n } from "../lib/i18n";
import { useRef, type ClipboardEvent, type KeyboardEvent } from "react";
import styles from "./CodeInput.module.css";

interface CodeInputProps {
  value: string;
  onChange: (value: string) => void;
  /** Called once all cells are filled, with the full code. */
  onComplete: (code: string) => void;
  length?: number;
  disabled?: boolean;
  invalid?: boolean;
  label?: string;
  describedBy?: string;
}

/** Six single-digit cells with auto-advance, backspace-to-previous and paste of the whole code. */
export function CodeInput({ value, onChange, onComplete, length = 6, disabled, invalid, label, describedBy }: CodeInputProps) {
  const { t } = useI18n();
  label ??= t("code.label");
  const cells = useRef<Array<HTMLInputElement | null>>([]);
  const digits = Array.from({ length }, (_, i) => value[i] ?? "");

  const focus = (i: number) => cells.current[Math.max(0, Math.min(length - 1, i))]?.focus();

  const commit = (next: string) => {
    const clean = next.replace(/\D/g, "").slice(0, length);
    onChange(clean);
    if (clean.length === length) onComplete(clean);
    return clean;
  };

  const setDigit = (i: number, typed: string) => {
    const onlyDigits = typed.replace(/\D/g, "");
    if (!onlyDigits) return;
    if (onlyDigits.length > 1) {
      const next = commit(value.slice(0, i) + onlyDigits);
      focus(next.length);
      return;
    }
    const chars = digits.slice();
    chars[i] = onlyDigits;
    const next = commit(chars.join(""));
    focus(Math.min(i + 1, next.length));
  };

  const onKeyDown = (i: number, e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Backspace") {
      e.preventDefault();
      if (digits[i]) {
        onChange(value.slice(0, i) + value.slice(i + 1));
      } else if (i > 0) {
        onChange(value.slice(0, i - 1) + value.slice(i));
        focus(i - 1);
      }
    } else if (e.key === "ArrowLeft") {
      e.preventDefault();
      focus(i - 1);
    } else if (e.key === "ArrowRight") {
      e.preventDefault();
      focus(i + 1);
    }
  };

  const onPaste = (e: ClipboardEvent<HTMLInputElement>) => {
    e.preventDefault();
    const next = commit(e.clipboardData.getData("text"));
    focus(next.length);
  };

  return (
    <div className={`${styles.code} ${invalid ? styles.invalid : ""}`} role="group" aria-label={label} aria-describedby={describedBy}>
      {digits.map((d, i) => (
        <input
          key={i}
          ref={(el) => {
            cells.current[i] = el;
          }}
          className={styles.cell}
          value={d}
          inputMode="numeric"
          autoComplete={i === 0 ? "one-time-code" : "off"}
          aria-label={t("code.digit", { n: i + 1, total: length })}
          maxLength={i === 0 ? length : 1}
          disabled={disabled}
          onChange={(e) => setDigit(i, e.target.value)}
          onKeyDown={(e) => onKeyDown(i, e)}
          onPaste={onPaste}
          onFocus={(e) => e.target.select()}
        />
      ))}
    </div>
  );
}
