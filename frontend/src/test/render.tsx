import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
import { AuthProvider } from "../lib/auth";
import { I18nProvider } from "../lib/i18n";

/** Shows the current location so tests can assert on redirects. */
function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname + location.search}</output>;
}

export function renderRoute(path: string, pattern: string, element: ReactElement) {
  return render(
    <I18nProvider>
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider>
          <Routes>
            <Route path={pattern} element={element} />
            <Route path="*" element={<p>elsewhere</p>} />
          </Routes>
          <LocationProbe />
        </AuthProvider>
      </MemoryRouter>
    </I18nProvider>,
  );
}
