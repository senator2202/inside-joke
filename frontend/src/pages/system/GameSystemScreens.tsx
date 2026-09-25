import { useI18n } from "../../lib/i18n";
import { Button, ButtonLink } from "../../components/Button";
import { SystemScreen } from "./SystemScreen";

/** X1: the room doesn't exist (wrong code, or it ended / the server restarted). */
export function RoomGone({ owner = false }: { owner?: boolean }) {
  const { t } = useI18n();
  return (
    <SystemScreen
      title={t("system.gone.title")}
      actions={
        <>
          <ButtonLink to="/join" variant={owner ? "secondary" : "primary"}>
            {t("system.gone.enterCode")}
          </ButtonLink>
          {owner && <ButtonLink to="/new">{t("system.gone.createNew")}</ButtonLink>}
        </>
      }
    />
  );
}

/** X2: the WebSocket never came up. */
export function CantConnect({ onRetry }: { onRetry: () => void }) {
  const { t } = useI18n();
  return (
    <SystemScreen title={t("system.cantConnect.title")} actions={<Button onClick={onRetry}>{t("common.retry")}</Button>}>
      <p>{t("system.cantConnect.text")}</p>
    </SystemScreen>
  );
}

/** Every copy of the shared screen this room allows is open, and each is in use or has only just closed. */
export function ScreensFull({ onRetry }: { onRetry: () => void }) {
  const { t } = useI18n();
  return (
    <SystemScreen title={t("system.screensFull.title")} actions={<Button onClick={onRetry}>{t("common.retry")}</Button>}>
      <p>{t("system.screensFull.text")}</p>
    </SystemScreen>
  );
}
