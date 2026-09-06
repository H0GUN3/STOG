package com.stog.backend.social;

import com.stog.backend.plan.TripMembershipPolicy;
import com.stog.backend.storage.PhotoRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PhotoCommentService {
    private final PhotoRepository photos;
    private final PhotoCommentRepository comments;
    private final TripMembershipPolicy memberships;

    public PhotoCommentService(
        PhotoRepository photos,
        PhotoCommentRepository comments,
        TripMembershipPolicy memberships
    ) {
        this.photos = photos;
        this.comments = comments;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public PhotoCommentResponses.Page list(long photoId) {
        requireEligiblePhoto(photoId);
        return new PhotoCommentResponses.Page(
            comments.findByPhoto(photoId).stream().map(this::response).toList()
        );
    }

    @Transactional
    public PhotoCommentResponses.Comment create(
        long userId,
        long photoId,
        PhotoCommentRequests.Create request
    ) {
        memberships.requireKnownUser(userId);
        requireEligiblePhoto(photoId);
        String body = request.body().trim();
        if (body.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment body is blank");
        }
        return response(comments.insert(userId, photoId, body));
    }

    private void requireEligiblePhoto(long photoId) {
        photos.lockFeedEligible(photoId).orElseThrow(() ->
            new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Eligible public feed photo not found"
            )
        );
    }

    private PhotoCommentResponses.Comment response(PhotoCommentRepository.CommentRow row) {
        return new PhotoCommentResponses.Comment(
            row.id(),
            row.photoId(),
            row.userId(),
            row.authorName(),
            row.body(),
            row.createdAt()
        );
    }
}
