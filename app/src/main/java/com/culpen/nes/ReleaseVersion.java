package com.culpen.nes;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The stable and numbered beta versions produced by the release workflow. */
public final class ReleaseVersion implements Comparable<ReleaseVersion> {
    private static final int MAX_VERSION_LENGTH = 256;
    private static final Pattern FORMAT = Pattern.compile(
            "v?((0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-beta\\.([1-9][0-9]*))?)");

    private final String name;
    private final BigInteger major;
    private final BigInteger minor;
    private final BigInteger patch;
    private final BigInteger beta;

    private ReleaseVersion(Matcher match) {
        name = match.group(1);
        major = new BigInteger(match.group(2));
        minor = new BigInteger(match.group(3));
        patch = new BigInteger(match.group(4));
        beta = match.group(5) == null ? null : new BigInteger(match.group(5));
    }

    /** Returns null for a version outside the project's canonical release format. */
    public static ReleaseVersion parse(String value) {
        if (value == null || value.length() > MAX_VERSION_LENGTH) return null;
        Matcher match = FORMAT.matcher(value);
        return match.matches() ? new ReleaseVersion(match) : null;
    }

    public String versionName() { return name; }

    public boolean isBeta() { return beta != null; }

    @Override public int compareTo(ReleaseVersion other) {
        int result = major.compareTo(other.major);
        if (result != 0) return result;
        result = minor.compareTo(other.minor);
        if (result != 0) return result;
        result = patch.compareTo(other.patch);
        if (result != 0) return result;
        if (beta == null) return other.beta == null ? 0 : 1;
        if (other.beta == null) return -1;
        return beta.compareTo(other.beta);
    }

    @Override public boolean equals(Object other) {
        return other instanceof ReleaseVersion && name.equals(((ReleaseVersion) other).name);
    }

    @Override public int hashCode() { return name.hashCode(); }

    @Override public String toString() { return name; }
}
