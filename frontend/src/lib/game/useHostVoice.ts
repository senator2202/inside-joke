import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Plays the host's voice lines on the shared screen. Browsers block audio until the page has been interacted with;
 * {@link blocked} tells the screen to show "🔊 Turn on the host's voice", and {@link enable} unlocks it.
 */
export function useHostVoice(audioId: string | undefined, enabledByDefault: boolean) {
  const [enabled, setEnabled] = useState(enabledByDefault);
  const [blocked, setBlocked] = useState(false);
  const [speaking, setSpeaking] = useState(false);
  const audio = useRef<HTMLAudioElement | null>(null);
  const lastPlayed = useRef<string | undefined>(undefined);

  useEffect(() => {
    if (typeof Audio === "undefined") return;
    const a = new Audio();
    a.preload = "auto";
    a.onplay = () => setSpeaking(true);
    a.onended = () => setSpeaking(false);
    a.onpause = () => setSpeaking(false);
    audio.current = a;
    return () => {
      a.pause();
      audio.current = null;
    };
  }, []);

  useEffect(() => {
    const a = audio.current;
    if (!a || !enabled || !audioId || audioId === lastPlayed.current) return;
    lastPlayed.current = audioId;
    a.src = `/api/voice-lines/${encodeURIComponent(audioId)}`;
    a.play().then(
      () => setBlocked(false),
      (e: unknown) => {
        if (e instanceof DOMException && e.name === "NotAllowedError") setBlocked(true);
      },
    );
  }, [audioId, enabled]);

  const enable = useCallback(() => {
    setEnabled(true);
    setBlocked(false);
    lastPlayed.current = undefined;
  }, []);
  const disable = useCallback(() => {
    setEnabled(false);
    audio.current?.pause();
  }, []);
  return { enabled, blocked, speaking, enable, disable };
}

/** Called from the "Create room" click: a silent play inside a user gesture unlocks audio for the next page. */
export function unlockAudio(): void {
  try {
    const a = new Audio("data:audio/mpeg;base64,SUQzBAAAAAAAI1RTU0UAAAAPAAADTGF2ZjU4LjI5LjEwMAAAAAAAAAAAAAAA");
    a.volume = 0;
    void a.play().catch(() => undefined);
  } catch {
    /* no audio support: text only */
  }
}
