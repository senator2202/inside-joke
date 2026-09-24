import { Route, Routes } from "react-router";
import { ErrorBoundary } from "./components/ErrorBoundary";
import { AuthProvider } from "./lib/auth";
import { I18nProvider } from "./lib/i18n";
import { AdminPage } from "./pages/admin/AdminPage";
import { AudiencePage } from "./pages/audience/AudiencePage";
import { AccountPage } from "./pages/host/AccountPage";
import { AuthCallbackPage } from "./pages/host/AuthCallbackPage";
import { LandingPage } from "./pages/host/LandingPage";
import { LoginPage } from "./pages/host/LoginPage";
import { NewPartyPage } from "./pages/host/NewPartyPage";
import { JoinPage } from "./pages/play/JoinPage";
import { PlayPage } from "./pages/play/PlayPage";
import { ScreenPage } from "./pages/screen/ScreenPage";
import { CrashScreen } from "./pages/system/CrashScreen";
import { NotFoundPage } from "./pages/system/NotFoundPage";

export function App() {
  return (
    <I18nProvider>
      <ErrorBoundary fallback={<CrashScreen />}>
        <AuthProvider>
          <Routes>
            <Route path="/" element={<LandingPage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/account" element={<AccountPage />} />
            <Route path="/admin" element={<AdminPage />} />
            <Route path="/auth/callback" element={<AuthCallbackPage />} />
            <Route path="/new" element={<NewPartyPage />} />
            <Route path="/screen/:code" element={<ScreenPage />} />
            <Route path="/view/:code" element={<ScreenPage remote />} />
            <Route path="/join" element={<JoinPage />} />
            <Route path="/j/:code" element={<JoinPage />} />
            <Route path="/play/:code" element={<PlayPage />} />
            <Route path="/w/:key" element={<AudiencePage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Routes>
        </AuthProvider>
      </ErrorBoundary>
    </I18nProvider>
  );
}
