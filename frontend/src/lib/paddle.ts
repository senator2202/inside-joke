/**
 * Thin wrapper over Paddle.js v2 (the overlay checkout). The script is loaded on demand from Paddle's CDN; an ad blocker
 * that stops it is reported as {@link PaddleUnavailable} so the picker can explain what to do.
 */
export interface CheckoutEvent {
  name: string;
  data?: { transaction_id?: string; [key: string]: unknown };
}

export interface PaddleApi {
  openCheckout(options: { priceId: string; email: string; customData: Record<string, string> }): void;
  closeCheckout(): void;
  /** Localized totals (tax-inclusive, in the visitor's currency) keyed by price id. */
  previewPrices(priceIds: string[]): Promise<Record<string, string>>;
  onEvent(listener: (event: CheckoutEvent) => void): () => void;
}

export class PaddleUnavailable extends Error {
  constructor() {
    super("Paddle.js could not be loaded");
    this.name = "PaddleUnavailable";
  }
}

interface PaddleGlobal {
  Environment: { set(env: string): void };
  Initialize(options: { token: string; eventCallback: (event: CheckoutEvent) => void }): void;
  Checkout: {
    open(options: Record<string, unknown>): void;
    close(): void;
  };
  PricePreview(request: { items: { priceId: string; quantity: number }[] }): Promise<{
    data: { details: { lineItems: { price: { id: string }; formattedTotals: { total: string } }[] } };
  }>;
}

const SCRIPT = "https://cdn.paddle.com/paddle/v2/paddle.js";
type Loader = (config: { clientToken: string; environment: string }) => Promise<PaddleApi>;

let instance: Promise<PaddleApi> | null = null;
let loaderOverride: Loader | null = null;

export function setPaddleLoaderForTests(loader: Loader | null): void {
  loaderOverride = loader;
  instance = null;
}

export function loadPaddle(config: { clientToken: string; environment: string }): Promise<PaddleApi> {
  if (loaderOverride) return loaderOverride(config);
  instance ??= realLoad(config).catch((e: unknown) => {
    instance = null;
    throw e;
  });
  return instance;
}

function injectScript(): Promise<PaddleGlobal> {
  const existing = (window as unknown as { Paddle?: PaddleGlobal }).Paddle;
  if (existing) return Promise.resolve(existing);
  return new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = SCRIPT;
    script.async = true;
    const timer = window.setTimeout(() => reject(new PaddleUnavailable()), 15_000);
    script.onload = () => {
      window.clearTimeout(timer);
      const paddle = (window as unknown as { Paddle?: PaddleGlobal }).Paddle;
      if (paddle) resolve(paddle);
      else reject(new PaddleUnavailable());
    };
    script.onerror = () => {
      window.clearTimeout(timer);
      script.remove();
      reject(new PaddleUnavailable());
    };
    document.head.appendChild(script);
  });
}

async function realLoad(config: { clientToken: string; environment: string }): Promise<PaddleApi> {
  const paddle = await injectScript();
  const listeners = new Set<(event: CheckoutEvent) => void>();
  if (config.environment === "sandbox") paddle.Environment.set("sandbox");
  paddle.Initialize({ token: config.clientToken, eventCallback: (event) => listeners.forEach((l) => l(event)) });
  return {
    openCheckout: ({ priceId, email, customData }) =>
      paddle.Checkout.open({
        items: [{ priceId, quantity: 1 }],
        customer: { email },
        customData,
        settings: { displayMode: "overlay", theme: "light", locale: "en", allowLogout: false, showAddDiscounts: false },
      }),
    closeCheckout: () => paddle.Checkout.close(),
    previewPrices: async (priceIds) => {
      const result = await paddle.PricePreview({ items: priceIds.map((priceId) => ({ priceId, quantity: 1 })) });
      return Object.fromEntries(result.data.details.lineItems.map((li) => [li.price.id, li.formattedTotals.total]));
    },
    onEvent: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}
