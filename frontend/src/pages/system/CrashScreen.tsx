import { useI18n } from "../../lib/i18n";
import { Button } from "../../components/Button";
import { SystemScreen } from "./SystemScreen";

/** X5: rendered by the error boundary. The game state lives on the server, so a reload resumes it. */
export function CrashScreen() {
  const { t } = useI18n();
  return (
    <SystemScreen
      title={t("system.crash.title")}
      actions={<Button onClick={() => window.location.reload()}>{t("system.crash.reload")}</Button>}
    >
      <p>{t("system.crash.text")}</p>
    </SystemScreen>
  );
}
