import { useI18n } from "../../lib/i18n";
import { QRCodeSVG } from "qrcode.react";
import styles from "./game.module.css";

export function JoinQr({ url, pulse }: { url: string; pulse?: boolean }) {
  const { t } = useI18n();
  return (
    <div className={styles.qr} data-pulse={pulse || undefined}>
      <QRCodeSVG value={url} size={512} level="M" marginSize={2} bgColor="#fbf9fe" fgColor="#1d1433" title={t("qr.title")} />
    </div>
  );
}
