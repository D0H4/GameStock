package com.gamestock.backend.auth;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Profile images are shown to every visitor of the ranking page. An arbitrary
 * external URL would let one player log the IP address of everyone who views
 * the ranking, so only our bundled avatars, photos uploaded to our own API,
 * and Google account photos (the picture Firebase returns for Google sign-in)
 * are accepted.
 */
public final class ProfileImages {
    private static final Pattern BUNDLED_AVATAR = Pattern.compile("/profile-avatars/[a-z0-9-]+\\.png");
    private static final String PHOTO_PREFIX = "/api/profile-photos/";
    private static final Pattern UPLOADED_PHOTO = Pattern.compile("/api/profile-photos/([0-9a-f]{32})\\.jpg");
    private static final String GOOGLE_PHOTO_HOST = "lh3.googleusercontent.com";

    private ProfileImages() {}

    /** Empty means "use the default avatar" and is always allowed. */
    public static boolean allowed(String url) {
        if (url == null || url.isEmpty()) return true;
        if (BUNDLED_AVATAR.matcher(url).matches() || uploadedPhotoId(url) != null) return true;
        try {
            URI uri = new URI(url);
            // Reject user-info and explicit ports so "https://host@other/" style tricks cannot pass.
            return "https".equals(uri.getScheme()) && GOOGLE_PHOTO_HOST.equals(uri.getHost())
                    && uri.getRawUserInfo() == null && uri.getPort() == -1;
        } catch (Exception error) {
            return false;
        }
    }

    /** The path clients resolve against the API host. */
    public static String photoUrl(String id) {
        return PHOTO_PREFIX + id + ".jpg";
    }

    /** The photo id when the URL points at an uploaded photo, otherwise null. */
    public static String uploadedPhotoId(String url) {
        if (url == null) return null;
        var match = UPLOADED_PHOTO.matcher(url);
        return match.matches() ? match.group(1) : null;
    }

    /** Returns the URL when allowed, otherwise an empty string (default avatar). */
    public static String sanitize(String url) {
        return allowed(url) ? (url == null ? "" : url) : "";
    }
}
