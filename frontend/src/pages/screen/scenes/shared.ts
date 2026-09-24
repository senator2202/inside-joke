import type { PlayerView, RoomView } from "../../../lib/game/types";

export interface SceneProps {
  state: RoomView;
  offset: number;
  owner: boolean;
  send: (type: string, data?: unknown) => void;
  speaking: boolean;
}

export function playerById(state: RoomView, id: string | undefined): PlayerView | undefined {
  return state.players?.find((p) => p.id === id);
}
