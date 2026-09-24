import { useEffect, useRef } from "react";
import { GameError, type RoomView } from "../../../lib/game/types";

export interface PhoneProps {
  state: RoomView;
  offset: number;
  request: (type: string, data?: unknown) => Promise<Record<string, unknown>>;
}

/** Runs the latest `action` once, 1.5 s before the server's deadline (so it arrives in time). */
export function useBeforeDeadline(deadline: number | undefined, offset: number, action: () => void): void {
  const latest = useRef(action);
  useEffect(() => {
    latest.current = action;
  });
  useEffect(() => {
    if (deadline === undefined) return;
    const t = setTimeout(() => latest.current(), Math.max(0, deadline - (Date.now() + offset) - 1500));
    return () => clearTimeout(t);
  }, [deadline, offset]);
}

export function codeOf(e: unknown): string | undefined {
  return e instanceof GameError ? e.code : undefined;
}

// ---------------------------------------------------------------- P2 lobby
