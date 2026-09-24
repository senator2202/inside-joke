import { useCallback, useEffect, useRef, useState } from "react";
import { GameConnection, type ConnectionStatus, type SocketFactory } from "./connection";
import type { RoomView } from "./types";

export interface GameHandle {
  state: RoomView | null;
  status: ConnectionStatus;
  /** Server time minus local time, for countdowns that match the server's deadline. */
  clockOffset: number;
  request: (type: string, data?: unknown) => Promise<Record<string, unknown>>;
  retry: () => void;
}

let socketFactoryOverride: SocketFactory | undefined;

/** Tests swap in a fake WebSocket. */
export function setSocketFactoryForTests(factory: SocketFactory | undefined): void {
  socketFactoryOverride = factory;
}

export function useGame(token: string | null): GameHandle {
  const [state, setState] = useState<RoomView | null>(null);
  const [status, setStatus] = useState<ConnectionStatus>("connecting");
  const [clockOffset, setClockOffset] = useState(0);
  const connection = useRef<GameConnection | null>(null);

  useEffect(() => {
    if (!token) return;
    const conn = new GameConnection(token, {
      socketFactory: socketFactoryOverride,
      onState: (next, offset) => {
        setState((prev) => (prev && prev.version > next.version ? prev : next));
        setClockOffset(offset);
      },
      onStatus: setStatus,
    });
    connection.current = conn;
    conn.start();
    return () => {
      conn.stop();
      connection.current = null;
    };
  }, [token]);

  const request = useCallback(
    (type: string, data: unknown = {}) =>
      connection.current ? connection.current.request(type, data) : Promise.reject(new Error("Not connected")),
    [],
  );
  const retry = useCallback(() => connection.current?.retry(), []);
  return { state, status, clockOffset, request, retry };
}
