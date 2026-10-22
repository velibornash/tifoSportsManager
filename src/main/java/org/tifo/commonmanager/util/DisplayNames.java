package org.tifo.commonmanager.util;

import org.tifo.commonmanager.model.User;

/**
 * The name to show for a manager: what he chose to call himself, or his login when he has not chosen one.
 *
 * <p>This was written out eleven times in nine files, and the copies did not all agree — most of them
 * trimmed the chosen name and four did not, so a display name with a stray space rendered with the stray
 * space next to a post and without it on the squad page. Nothing catastrophic, and exactly the reason a
 * self-registered manager saw his email address beside his own messages: the copy that decided to use the
 * login was not the same code as the copy that decided to use the name.
 *
 * <p>The rule, once:
 *
 * <ol>
 *   <li>a display name that holds anything at all is the answer, trimmed;</li>
 *   <li>otherwise the login is the answer — never null, never blank.</li>
 * </ol>
 *
 * <p>This is a <b>display</b> fallback and not an identity. The login is what a manager signs in with, and
 * code that has to know whether a name was chosen must ask that question directly
 * ({@code user.getDisplayName() != null && !user.getDisplayName().isBlank()}) — several callers do, to
 * label a row "has not chosen a name yet".
 */
public final class DisplayNames {

    private DisplayNames() {
    }

    /** What to show for this manager, trimmed, never blank. */
    public static String of(User user) {
        if (user == null) {
            return null;
        }
        String chosen = user.getDisplayName();
        if (chosen != null && !chosen.isBlank()) {
            return chosen.trim();
        }
        return user.getUsername();
    }

    /** Whether this manager has chosen a name, rather than being shown by his login. */
    public static boolean hasChosen(User user) {
        return user != null && user.getDisplayName() != null && !user.getDisplayName().isBlank();
    }
}
