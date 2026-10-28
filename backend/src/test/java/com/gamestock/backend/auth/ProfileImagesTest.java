package com.gamestock.backend.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProfileImagesTest {
    @Test void acceptsDefaultBundledAvatarsAndGooglePhotos() {
        assertTrue(ProfileImages.allowed(null));
        assertTrue(ProfileImages.allowed(""));
        assertTrue(ProfileImages.allowed("/profile-avatars/mint-hood-384.png"));
        assertTrue(ProfileImages.allowed("https://lh3.googleusercontent.com/a/ACg8ocK=s96-c"));
    }

    @Test void rejectsUrlsThatWouldLeakViewerAddresses() {
        assertFalse(ProfileImages.allowed("https://tracker.example/pixel.png"));
        assertFalse(ProfileImages.allowed("http://lh3.googleusercontent.com/a/photo"));
        assertFalse(ProfileImages.allowed("https://lh3.googleusercontent.com@tracker.example/a"));
        assertFalse(ProfileImages.allowed("https://lh3.googleusercontent.com.tracker.example/a"));
        assertFalse(ProfileImages.allowed("https://lh3.googleusercontent.com:8443/a"));
        assertFalse(ProfileImages.allowed("//tracker.example/profile-avatars/x.png"));
        assertFalse(ProfileImages.allowed("/profile-avatars/../../tracker.png"));
        assertFalse(ProfileImages.allowed("javascript:alert(1)"));
    }

    @Test void acceptsOnlyWellFormedUploadedPhotoPaths() {
        assertTrue(ProfileImages.allowed("/api/profile-photos/0123456789abcdef0123456789abcdef.jpg"));
        assertEquals("0123456789abcdef0123456789abcdef", ProfileImages.uploadedPhotoId(ProfileImages.photoUrl("0123456789abcdef0123456789abcdef")));
        assertFalse(ProfileImages.allowed("/api/profile-photos/../ranking.jpg"));
        assertFalse(ProfileImages.allowed("/api/profile-photos/0123456789ABCDEF0123456789ABCDEF.jpg"));
        assertFalse(ProfileImages.allowed("https://tracker.example/api/profile-photos/0123456789abcdef0123456789abcdef.jpg"));
        assertNull(ProfileImages.uploadedPhotoId("/profile-avatars/mint-hood-384.png"));
    }

    @Test void sanitizeFallsBackToDefaultAvatar() {
        assertEquals("", ProfileImages.sanitize(null));
        assertEquals("", ProfileImages.sanitize("https://tracker.example/pixel.png"));
        assertEquals("/profile-avatars/teal-leaf-384.png", ProfileImages.sanitize("/profile-avatars/teal-leaf-384.png"));
    }
}
