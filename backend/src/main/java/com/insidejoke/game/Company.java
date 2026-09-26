package com.insidejoke.game;

/**
 * Who came to the party (roadmap R39). It decides what is fitting: the host writes differently for old friends,
 * coworkers or a family, and a group that must stay clean gets neither the Spicy tone nor its prewritten content.
 */
public enum Company {
    /** Old friends: shared history, free roasting. */
    FRIENDS(false),
    /** Coworkers: office life only; nothing HR would flag. */
    COLLEAGUES(true),
    /** Family, maybe with kids and grandparents. */
    FAMILY(true),
    /** Couples, a double date. */
    COUPLES(false),
    /** People who have only just met: no shared history to lean on. */
    ACQUAINTANCES(false);

    private final boolean clean;

    Company(boolean clean) {
        this.clean = clean;
    }

    /** Whether this group must stay clean: no Spicy tone, and family-safe prewritten content whatever the tone. */
    public boolean clean() {
        return clean;
    }

    public boolean allows(Tone tone) {
        return !clean || tone != Tone.SPICY;
    }
}
