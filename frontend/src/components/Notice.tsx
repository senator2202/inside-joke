import type { ReactNode } from "react";
import styles from "./Notice.module.css";

type Tone = "info" | "error" | "success";

export function Notice({ tone = "info", children, title }: { tone?: Tone; title?: string; children?: ReactNode }) {
  return (
    <div className={`${styles.notice} ${styles[tone]}`} role={tone === "error" ? "alert" : "status"}>
      {title && <p className={styles.title}>{title}</p>}
      {children && <div className={styles.body}>{children}</div>}
    </div>
  );
}
