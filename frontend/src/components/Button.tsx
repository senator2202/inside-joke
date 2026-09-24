import type { ButtonHTMLAttributes, ReactNode } from "react";
import { Link, type LinkProps } from "react-router";
import styles from "./Button.module.css";
import { Spinner } from "./Spinner";

type Variant = "primary" | "secondary" | "quiet" | "danger";

interface Look {
  variant?: Variant;
  block?: boolean;
  big?: boolean;
}

function classes({ variant = "primary", block, big }: Look, loading = false, extra?: string): string {
  return [
    styles.button,
    variant !== "primary" ? styles[variant] : "",
    block ? styles.block : "",
    big ? styles.big : "",
    loading ? styles.loading : "",
    extra ?? "",
  ]
    .filter(Boolean)
    .join(" ");
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement>, Look {
  loading?: boolean;
  loadingLabel?: string;
  children: ReactNode;
}

export function Button({ variant, block, big, loading = false, loadingLabel, className, children, disabled, type, ...rest }: ButtonProps) {
  return (
    <button
      {...rest}
      type={type ?? "button"}
      className={classes({ variant, block, big }, loading, className)}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
    >
      <span className={styles.label}>{children}</span>
      {loading && (
        <span className={styles.spinnerSlot}>
          <Spinner label={loadingLabel} />
        </span>
      )}
    </button>
  );
}

export interface ButtonLinkProps extends LinkProps, Look {
  children: ReactNode;
}

export function ButtonLink({ variant, block, big, className, children, ...rest }: ButtonLinkProps) {
  return (
    <Link {...rest} className={classes({ variant, block, big }, false, className)}>
      <span className={styles.label}>{children}</span>
    </Link>
  );
}
