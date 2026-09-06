package com.stog.backend.profile;

import com.stog.backend.auth.User;
import com.stog.backend.auth.UserRepository;
import com.stog.backend.storage.GcsSignedUrlService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("gcs-write")
public class ProfileAvatarService {
    private final UserRepository users;
    private final GcsSignedUrlService storage;

    public ProfileAvatarService(UserRepository users, GcsSignedUrlService storage) {
        this.users = users;
        this.storage = storage;
    }

    public ProfileResponses.AvatarUploadUrl issueUploadUrl(
        long userId,
        ProfileRequests.AvatarUpload request
    ) {
        user(userId);
        try {
            GcsSignedUrlService.ProfileImageUpload upload = storage.issueProfileImageUpload(
                Long.toString(userId),
                request.client_upload_id(),
                request.content_type(),
                request.size_bytes(),
                request.sha256()
            );
            return new ProfileResponses.AvatarUploadUrl(
                upload.objectKey(),
                upload.uploadUrl(),
                upload.contentType(),
                upload.uploadHeaders(),
                upload.expiresAt()
            );
        } catch (IllegalArgumentException error) {
            throw badRequest(error.getMessage());
        }
    }

    @Transactional
    public void finalizeUpload(long userId, ProfileRequests.AvatarUpload request) {
        User user = user(userId);
        try {
            String objectKey = storage.verifyProfileImageUpload(
                Long.toString(userId),
                request.client_upload_id(),
                request.content_type(),
                request.size_bytes(),
                request.sha256()
            );
            user.setProfileImageKey(objectKey);
        } catch (IllegalArgumentException error) {
            throw badRequest(error.getMessage());
        }
    }

    private User user(long userId) {
        return users.findById(userId).orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "User was not found"
        ));
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
