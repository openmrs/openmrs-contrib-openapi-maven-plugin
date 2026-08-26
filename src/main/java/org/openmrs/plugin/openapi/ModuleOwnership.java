package org.openmrs.plugin.openapi;

import java.io.File;
import java.security.CodeSource;
import java.util.Collection;
import java.util.Set;

/**
 * Decides whether a discovered class belongs to the module being documented, by comparing the
 * location it was loaded from against the module's own build output and sibling artifacts.
 * <p>
 * The classpath scan deliberately finds every resource and controller on the classpath, including
 * those from dependency JARs — the schema resolver needs all of them so cross-module {@code $ref}
 * targets can be named. Only module-owned classes are written to disk, so counts reported to the
 * user should be module-owned counts.
 */
final class ModuleOwnership {

    private ModuleOwnership() {
    }

    /**
     * @param ownedLocations canonical paths of this module's build output and sibling artifacts;
     *                       when null or empty every class counts as owned
     * @return true if {@code cls} was loaded from one of {@code ownedLocations}
     */
    static boolean isOwned(Class<?> cls, Set<String> ownedLocations) {
        if (ownedLocations == null || ownedLocations.isEmpty()) {
            return true;
        }
        try {
            CodeSource codeSource = cls.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return false;
            }
            File classLocation = new File(codeSource.getLocation().toURI()).getCanonicalFile();
            for (String owned : ownedLocations) {
                if (classLocation.equals(new File(owned).getCanonicalFile())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Counts how many of the given classes belong to this module. */
    static int countOwned(Collection<Class<?>> classes, Set<String> ownedLocations) {
        int owned = 0;
        for (Class<?> cls : classes) {
            if (isOwned(cls, ownedLocations)) {
                owned++;
            }
        }
        return owned;
    }
}
