import { useI18n } from "../../lib/i18n";
import { ButtonLink } from "../../components/Button";
import { SystemScreen } from "./SystemScreen";

/** X4. */
export function NotFoundPage() {
  const { t } = useI18n();
  return (
    <SystemScreen title={t("system.notFound.title")} actions={<ButtonLink to="/">{t("system.notFound.home")}</ButtonLink>}>
      <p>{t("system.notFound.text")}</p>
    </SystemScreen>
  );
}
