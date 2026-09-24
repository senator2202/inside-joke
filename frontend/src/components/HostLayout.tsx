import { useI18n } from "../lib/i18n";
import { LanguagePicker } from "./LanguagePicker";
import type { ReactNode } from "react";
import { Link } from "react-router";
import { useAuth } from "../lib/auth";
import styles from "./HostLayout.module.css";
import { Logo } from "./Logo";

/** Frame for the host's prep pages (H screens): paper background, header with sign-in state, footer links. */
export function HostLayout({ children, narrow = false, header = true }: { children: ReactNode; narrow?: boolean; header?: boolean }) {
  const { me, loading } = useAuth();
  const { t } = useI18n();
  return (
    <div className={styles.frame}>
      {header && (
        <header className={styles.header}>
          <Logo />
          <nav aria-label={t("layout.accountNav")} className={styles.nav}>
            <LanguagePicker />
            {!loading && me && (
              <Link to="/account" className={styles.account} aria-label={t("layout.myAccount")}>
                <span className={styles.avatar} aria-hidden="true">
                  {(me.displayName ?? me.email).slice(0, 1).toUpperCase()}
                </span>
                <span className={styles.accountName}>{me.displayName ?? me.email}</span>
              </Link>
            )}
            {!loading && !me && (
              <Link to="/login" className={styles.signIn}>
                {t("layout.signIn")}
              </Link>
            )}
          </nav>
        </header>
      )}
      <main className={`${styles.main} ${narrow ? styles.narrow : ""}`}>{children}</main>
      <footer className={styles.footer}>
        <a href="/terms.html">{t("layout.terms")}</a>
        <a href="/privacy.html">{t("layout.privacy")}</a>
        <a href="mailto:hello@insidejoke.app">hello@insidejoke.app</a>
      </footer>
    </div>
  );
}
