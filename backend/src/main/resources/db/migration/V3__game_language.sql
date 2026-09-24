-- game_session.language (added in V2, filled from now on) holds the room language: 'en' or 'ru'.
ALTER TABLE game_session ADD CONSTRAINT ck_game_session_language CHECK (language IN ('en', 'ru'));
