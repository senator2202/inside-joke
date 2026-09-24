import type { ReactNode } from "react";
import { LanguagePicker } from "../../components/LanguagePicker";
import { Logo } from "../../components/Logo";
import styles from "./SystemScreen.module.css";

/** Shared frame for the X screens: night background, one message, one action. */
export function SystemScreen({ title, children, actions }: { title: string; children?: ReactNode; actions: ReactNode }) {
  return (
    <div className={`night ${styles.frame}`}>
      <header className={styles.header}>
        <Logo />
        <LanguagePicker tone="night" />
      </header>
      <main className={styles.main}>
        <div className={styles.redaction} aria-hidden="true">
          <span />
          <span />
        </div>
        <h1 className={styles.title}>{title}</h1>
        {children && <div className={styles.body}>{children}</div>}
        <div className={styles.actions}>{actions}</div>
      </main>
    </div>
  );
}
