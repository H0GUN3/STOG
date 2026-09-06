package com.stog.backend.plan;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TravelGuideAiApplyService {
    private final TripMembershipPolicy memberships;
    private final ItineraryRepository itineraries;
    private final TravelGuideAiProposalRepository proposals;

    public TravelGuideAiApplyService(
        TripMembershipPolicy memberships,
        ItineraryRepository itineraries,
        TravelGuideAiProposalRepository proposals
    ) {
        this.memberships = memberships;
        this.itineraries = itineraries;
        this.proposals = proposals;
    }

    @Transactional
    public TravelGuideAiResponses.Applied apply(
        long userId,
        ProposalScope scope,
        TravelGuideAiRequests.Apply request
    ) {
        long tripId = scope.tripId();
        UUID suggestionId = scope.suggestionId();
        memberships.requireActiveMember(userId, tripId);
        TravelGuideAiProposalRepository.StoredProposal proposal = proposals.lock(suggestionId)
            .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "PROPOSAL_NOT_FOUND", "Proposal was not found"));
        if (proposal.tripId() != tripId) {
            throw failure(HttpStatus.CONFLICT, "PROPOSAL_WRONG_TRIP", "Proposal belongs to another trip");
        }
        if (!proposal.fingerprint().equals(request.proposal_fingerprint())) {
            throw failure(HttpStatus.CONFLICT, "PROPOSAL_REPLAY_CONFLICT", "Proposal payload changed");
        }
        if ("applied".equals(proposal.status())) {
            return replay(proposal, request.client_apply_id());
        }
        if (!proposal.feasible()) {
            throw failure(HttpStatus.CONFLICT, "PROPOSAL_INVALID", "Invalid proposal cannot be applied");
        }
        long version = itineraries.lockVersion(tripId);
        if (version != proposal.baseVersion()) {
            throw failure(HttpStatus.CONFLICT, "PROPOSAL_STALE", "Itinerary changed after preview");
        }
        List<Long> actionIds = proposal.actions().stream()
            .map(TravelGuideAiResponses.Action::basket_item_id)
            .toList();
        Set<Long> resolvedIds = proposals.resolvedBasketItemIds(tripId, actionIds);
        if (resolvedIds.size() != actionIds.size()) {
            throw failure(HttpStatus.CONFLICT, "PROPOSAL_STALE", "Proposal place resolution changed");
        }
        List<ItineraryRequests.Item> items = proposal.actions().stream()
            .map(TravelGuideAiApplyService::toItineraryItem)
            .toList();
        ItineraryRepository.AppliedWrite write = itineraries.applyProposal(
            new ItineraryRepository.ItineraryWriteScope(userId, tripId), items, version
        );
        proposals.markApplied(new TravelGuideAiProposalRepository.ApplyReceipt(
            suggestionId, request.client_apply_id(), request.proposal_fingerprint(), write
        ));
        return new TravelGuideAiResponses.Applied(
            suggestionId, tripId, "applied", write.version(), write.changeId(), write.items()
        );
    }

    private TravelGuideAiResponses.Applied replay(
        TravelGuideAiProposalRepository.StoredProposal proposal,
        UUID clientApplyId
    ) {
        if (!clientApplyId.equals(proposal.appliedClientId())) {
            throw failure(HttpStatus.CONFLICT, "PROPOSAL_REPLAY_CONFLICT", "Apply key changed");
        }
        return new TravelGuideAiResponses.Applied(
            proposal.id(), proposal.tripId(), "applied", proposal.appliedVersion(),
            proposal.appliedChangeId(), itineraries.findByTrip(proposal.tripId())
        );
    }

    private static ItineraryRequests.Item toItineraryItem(TravelGuideAiResponses.Action action) {
        return new ItineraryRequests.Item(
            action.basket_item_id(), action.day_number(), action.order_index(),
            action.planned_arrival(), action.planned_duration_min(), action.is_fixed()
        );
    }

    private static TravelGuideAiException failure(
        HttpStatus status,
        String code,
        String message
    ) {
        return new TravelGuideAiException(status, code, message);
    }

    public record ProposalScope(long tripId, UUID suggestionId) {
    }
}
